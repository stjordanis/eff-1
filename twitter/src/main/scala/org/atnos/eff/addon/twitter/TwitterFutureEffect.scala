package org.atnos.eff.addon.twitter

import cats._
import cats.implicits._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._
import org.atnos.eff.{NoFx, interpret, _}
import io.catbird.util._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.TimeoutException
import com.twitter.util._

object TwitterFutureCreation extends TwitterFutureCreation

final case class TwitterTimedFuture[A](callback: ExecutorServices => Future[A], timeout: Option[FiniteDuration] = None) {
  @inline def runNow(ex: ExecutorServices): Future[A] = {
    timeout.fold {
      callback(ex)
    } { t =>
      val promise = Promise[A]
      val timeout = new Runnable {
        def run(): Unit = {
          val _ = promise.updateIfEmpty(Throw(new TimeoutException))
        }
      }
      ex.scheduledExecutorService.schedule(timeout, t.length, t.unit)
      callback(ex).onFailure { e => val _ = promise.updateIfEmpty(Throw(e)) }.onSuccess { a => val _ = promise.updateIfEmpty(Return(a)) }
      promise
    }
  }
}

object TwitterTimedFuture {

  final def ApplicativeTwitterTimedFuture: Applicative[TwitterTimedFuture] = new Applicative[TwitterTimedFuture] {
    def pure[A](x: A): TwitterTimedFuture[A] =
      TwitterTimedFuture(_ => Future.value(x))

    def ap[A, B](ff: TwitterTimedFuture[A => B])(fa: TwitterTimedFuture[A]): TwitterTimedFuture[B] = {
      TwitterTimedFuture { ex: ExecutorServices =>
        val ffRan = ff.runNow(ex)
        val faRan = fa.runNow(ex)
        ffRan.joinWith(faRan)(_(_))
      }
    }

    override def toString = "Applicative[TwitterTimedFuture]"
  }

  implicit final def MonadTwitterTimedFuture: Monad[TwitterTimedFuture] = new Monad[TwitterTimedFuture] {
    def pure[A](x: A): TwitterTimedFuture[A] =
      TwitterTimedFuture(_ => Future.value(x))

    def flatMap[A, B](fa: TwitterTimedFuture[A])(f: A => TwitterTimedFuture[B]): TwitterTimedFuture[B] =
      TwitterTimedFuture[B](ex => fa.runNow(ex).flatMap(f(_).runNow(ex)))

    def tailRecM[A, B](a: A)(f: A => TwitterTimedFuture[Either[A, B]]): TwitterTimedFuture[B] =
      TwitterTimedFuture[B]({ ex =>
        def loop(va: A): Future[B] = f(va).runNow(ex).flatMap {
          case Left(na) => loop(na)
          case Right(nb) => Future.value(nb)
        }
        loop(a)
      })

    override def toString = "Monad[TwitterTimedFuture]"
  }
}

trait TwitterFutureTypes {

  type _future[R] = TwitterTimedFuture |= R
  type _Future[R] = TwitterTimedFuture <= R

}

trait TwitterFutureCreation extends TwitterFutureTypes {

  final def futureWithExecutors[R: _future, A](c: ExecutorServices => Future[A], timeout: Option[FiniteDuration] = None): Eff[R, A] =
    send[TwitterTimedFuture, R, A](TwitterTimedFuture(c, timeout))

  final def future[R: _future, A](c: => Future[A], timeout: Option[FiniteDuration] = None): Eff[R, A] =
    send[TwitterTimedFuture, R, A](TwitterTimedFuture(_ => c, timeout))

  final def futureCB[R: _future, A](c: ExecutorServices => Future[A],
                                    timeout: Option[FiniteDuration] = None): Eff[R, A] =
    send[TwitterTimedFuture, R, A](TwitterTimedFuture(c, timeout))

  final def futureFailed[R: _future, A](t: Throwable): Eff[R, A] =
    send[TwitterTimedFuture, R, A](TwitterTimedFuture(_ => Future.exception(t)))

  final def futureFromEither[R: _future, A](e: Throwable Either A): Eff[R, A] =
    e.fold(futureFailed[R, A], Eff.pure[R, A])

  final def futureDelay[R: _future, A](a: => A, timeout: Option[FiniteDuration] = None): Eff[R, A] =
    send[TwitterTimedFuture, R, A](TwitterTimedFuture(_ => Future.apply(a), timeout))

  final def futureFork[R: _future, A](a: => A, timeout: Option[FiniteDuration] = None): Eff[R, A] =
    send[TwitterTimedFuture, R, A](TwitterTimedFuture(c => FuturePool(c.executorService)(a), timeout))
}

trait TwitterFutureInterpretation extends TwitterFutureTypes {

  final def futureAttempt[R, A](e: Eff[R, A])(implicit future: TwitterTimedFuture /= R): Eff[R, Throwable Either A] = {
    e match {
      case Pure(a, last) =>
        pure[R, Throwable Either A](Either.right(a)).addLast(last)

      case Impure(u, c, last) =>
        future.extract(u) match {
          case Some(tx) =>
            val union = future.inject(attempt(tx))

            Impure(union, Arrs.singleton { ex: (Throwable Either u.X) =>
              ex match {
                case Right(x) => futureAttempt(c(x))
                case Left(t) => pure(Either.left(t))
              }
            }, last)

          case None => Impure(u, Arrs.singleton((x: u.X) => futureAttempt(c(x))), last)
        }

      case ImpureAp(unions, continuation, last) =>
        def materialize(u: Union[R, Any]): Union[R, Any] =
          future.extract(u) match {
            case Some(tx) => future.inject(attempt(tx).asInstanceOf[TwitterTimedFuture[Any]])
            case None => u
          }

        val materializedUnions =
          Unions(materialize(unions.first), unions.rest.map(materialize))

        val collected = unions.extract(future)
        val continuation1 = Arrs.singleton[R, List[Any], Throwable Either A] { ls: List[Any] =>
          val xors =
            ls.zipWithIndex.collect { case (a, i) =>
              if (collected.indices.contains(i)) a.asInstanceOf[Throwable Either Any]
              else Either.right(a)
            }.sequence

          xors match {
            case Left(t) => pure(Either.left(t))
            case Right(anys) => futureAttempt(continuation(anys))
          }
        }

        ImpureAp(materializedUnions, continuation1, last)
    }
  }

  final def attempt[A](a: TwitterTimedFuture[A]): TwitterTimedFuture[Throwable Either A] = {
    TwitterTimedFuture[Throwable Either A](callback = ex => {
      a.runNow(ex).liftToTry.map {
        case Throw(ex) => Either.left(ex)
        case Return(v) => Either.right(v)
      }
    })
  }

  final def memoize[A](key: AnyRef, cache: Cache, future: TwitterTimedFuture[A]): TwitterTimedFuture[A] =
    TwitterTimedFuture { ex =>
      val prom = Promise[A]()
      cache.get[A](key).fold {
        future.runNow(ex).map { v => val _ = cache.put(key, v); v }.proxyTo(prom)
      } { v => prom.setValue(v) }
      prom
    }

  implicit final def toTwitterFutureOps[R, A](e: Eff[R, A]): TwitterFutureOps[R, A] = new TwitterFutureOps[R, A](e)

  /**
    * Memoize future values using a cache
    *
    * if this method is called with the same key the previous value will be returned
    */
  final def futureMemo[R, A](key: AnyRef, cache: Cache, e: Eff[R, A])(implicit future: TwitterTimedFuture /= R): Eff[R, A] = {
    e match {
      case Pure(a, last) =>
        Pure(a, last)

      case Impure(u, c, last) =>
        future.extract(u) match {
          case Some(tx) => Impure(future.inject(memoize(key, cache, tx)), Arrs.singleton((x: u.X) => futureMemo(key, cache, c(x))), last)
          case None => Impure(u, Arrs.singleton((x: u.X) => futureMemo(key, cache, c(x))), last)
        }

      case ImpureAp(unions, continuation, last) =>
        def materialize(u: Union[R, Any]): Union[R, Any] =
          future.extract(u) match {
            case Some(tx) => future.inject(memoize(key, cache, tx))
            case None => u
          }

        val materializedUnions =
          Unions(materialize(unions.first), unions.rest.map(materialize))

        val continuation1 = Arrs.singleton[R, List[Any], A]((ls: List[Any]) => futureMemo(key, cache, continuation(ls)))
        ImpureAp(materializedUnions, continuation1, last)
    }
  }

  /**
    * Memoize Future values using a memoization effect
    *
    * if this method is called with the same key the previous value will be returned
    */
  final def futureMemoized[R, A](key: AnyRef, e: Eff[R, A])(implicit future: TwitterTimedFuture /= R, m: Memoized |= R): Eff[R, A] =
    MemoEffect.getCache[R].flatMap(cache => futureMemo(key, cache, e))

  def runFutureMemo[R, U, A](cache: Cache)(effect: Eff[R, A])(implicit m: Member.Aux[Memoized, R, U], task: TwitterTimedFuture |= U): Eff[U, A] = {
    interpret.translate(effect)(new Translate[Memoized, U] {
      def apply[X](mx: Memoized[X]): Eff[U, X] =
        mx match {
          case Store(key, value) => TwitterFutureEffect.futureDelay(cache.memo(key, value()))
          case GetCache()        => TwitterFutureEffect.futureDelay(cache)
        }
    })
  }

}

final class TwitterFutureOps[R, A](val e: Eff[R, A]) extends AnyVal {

  def runTwitterFutureMemo[U](cache: Cache)(implicit memMember: Member.Aux[Memoized, R, U],
                                            futMember: TwitterTimedFuture |= U): Eff[U, A] =
    TwitterFutureEffect.runFutureMemo(cache)(e)(memMember, futMember)

  def twitterFutureAttempt(implicit future: TwitterTimedFuture /= R): Eff[R, Throwable Either A] =
    TwitterFutureInterpretation.futureAttempt(e)

  def twitterFutureMemo(key: AnyRef, cache: Cache)(implicit future: TwitterTimedFuture /= R): Eff[R, A] =
    TwitterFutureInterpretation.futureMemo(key, cache, e)
}

object TwitterFutureInterpretation extends TwitterFutureInterpretation

trait TwitterFutureEffect extends TwitterFutureCreation with TwitterFutureInterpretation

object TwitterFutureEffect extends TwitterFutureEffect
