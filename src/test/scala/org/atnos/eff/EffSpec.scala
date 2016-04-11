package org.atnos.eff

import com.ambiata.disorder.PositiveIntSmall
import org.scalacheck.Arbitrary._
import org.scalacheck._
import Eff._
import Effects._
import ReaderEffect._
import WriterEffect._
import org.specs2.{ScalaCheck, Specification}
import cats.data._
import cats.syntax.all._
import cats.std.all._
import cats.MonadFilter
import algebra.Eq
import cats.laws.discipline.{arbitrary => _, _}
import CartesianTests._, Isomorphisms._
import syntax.eff._

class EffSpec extends Specification with ScalaCheck { def is = s2"""

 The Eff monad respects the laws            $laws

 run the reader monad with a pure operation $readerMonadPure
 run the reader monad with a bind operation $readerMonadBind
 run the writer monad twice                 $writerTwice

 run a reader/writer action $readerWriter

 The Eff monad is stack safe with Writer                 $stacksafeWriter
 The Eff monad is stack safe with Reader                 $stacksafeReader
 The Eff monad is stack safe with both Reader and Writer $stacksafeReaderWriter

 It is possible to run a Eff value with no effects $noEffect
 It is possible to run a Eff value with just one effect and get it back $oneEffect

 Eff can be used as MonadFilter if one effect is doing the filtering $monadFilter
 MonadFilterLaws are respected if one effect is doing the filtering $monadFilterLaws

"""

  def laws =
    MonadTests[F].monad[Int, Int, Int].all

  def readerMonadPure = prop { (initial: Int) =>
    type R[A] = Reader[Int, A]
    type S = R |: NoEffect

    run(runReader(initial)(ask[S, Int](ReaderMemberFirst))) === initial
  }

  def readerMonadBind = prop { (initial: Int) =>
    type R[A] = Reader[Int, A]
    type S = R |: NoEffect

    import ReaderImplicits._

    val read: Eff[S, Int] =
      for {
        i <- ask[S, Int]
        j <- ask[S, Int]
      } yield i + j

    run(runReader(initial)(read)) === initial * 2
  }

  def writerTwice = prop { _ : Int =>
    type W[A] = Writer[String, A]
    type S = W |: NoEffect

    val write: Eff[S, Unit] =
      for {
        _ <- tell("hello")
        _ <- tell("world")
      } yield ()

    run(runWriter(write)) ==== (((), List("hello", "world")))
  }

  def readerWriter = prop { init: PositiveIntSmall =>

    // define a Reader / Writer stack
    type W[A] = Writer[String, A]
    type R[A] = Reader[Int, A]
    type S = W |: R |: NoEffect

    object SImplicits extends MemberImplicits with ReaderImplicits with WriterImplicits
    import SImplicits._

    // create actions
    val readWrite: Eff[S, Int] =
      for {
        i <- ask[S, Int]
        _ <- tell[S, String]("initial="+i)
        j <- ask[S, Int]
        _ <- tell[S, String]("result="+(i+j))
      } yield i + j

    // run effects
    val initial = init.value
    run(runReader(initial)(runWriter(readWrite))) must_==
      ((initial * 2, List("initial="+initial, "result="+(initial*2))))
  }

  def stacksafeWriter = {
    type WriterString[A] = Writer[String, A]
    type E = WriterString |: NoEffect

    val list = (1 to 5000).toList
    val action = list.traverseU(i => WriterEffect.tell[E, String](i.toString))

    run(WriterEffect.runWriter(action)) ==== ((list.as(()), list.map(_.toString)))
  }

  def stacksafeReader = {
    type ReaderString[A] = Reader[String, A]
    type E = ReaderString |: NoEffect
    import ReaderImplicits._

    val list = (1 to 5000).toList
    val action = list.traverseU(i => ReaderEffect.ask[E, String])

    run(ReaderEffect.runReader("h")(action)) ==== list.as("h")
  }

  def stacksafeReaderWriter = {
    type ReaderString[A] = Reader[String, A]
    type WriterString[A] = Writer[String, A]

    type E = ReaderString |: WriterString |: NoEffect

    val list = (1 to 5000).toList
    val action = list.traverseU(i => ReaderEffect.ask[E, String] >>= WriterEffect.tell[E, String])

    run(WriterEffect.runWriter(ReaderEffect.runReader("h")(action))) ==== ((list.as(()), list.as("h")))
  }

  def noEffect =
    EvalEffect.runEval(EvalEffect.delay(1)).run === 1

  def oneEffect =
    EvalEffect.delay(1).detach.value === 1

  def monadFilter = {
    type ReaderString[A] = Reader[String, A]

    type E = ReaderString |: Option |: NoEffect

    val action: Eff[E, Int] = for {
      _ <- ask[E, String]
      i <- OptionEffect.some[E, Int](10)
    } yield i

    val filtered =
      MonadFilter[Eff[E, ?]](EffMonadFilter[E, Option]).filterM(action)((i: Int) => EffMonad[E].pure(i > 10))

    run(OptionEffect.runOption(ReaderEffect.runReader("h")(filtered))) ==== None
  }

  def monadFilterLaws = {
    type R = Option |: NoEffect
    implicit val m = Eff.EffMonadFilter[R, Option]

    MonadFilterTests[Eff[R, ?]].monadFilter[Int, Int, Int].all
  }


  /**
   * Helpers
   */
  type F[A] = Eff[Option |: NoEffect, A]

  implicit def ArbitraryEff[R]: Arbitrary[Eff[R, Int]] = Arbitrary[Eff[R, Int]] {
    Gen.oneOf(
      Gen.choose(0, 100).map(i => EffMonad[R].pure(i)),
      Gen.choose(0, 100).map(i => EffMonad[R].pure(i).map(_ + 10))
    )
  }

  implicit def ArbitraryEffFunction[R]: Arbitrary[Eff[R, Int => Int]] =
    Arbitrary(arbitrary[Int => Int].map(f => EffMonad[R].pure(f)))

  import OptionEffect._

  implicit val eqEffInt: Eq[F[Int]] = new Eq[F[Int]] {
    def eqv(x: F[Int], y: F[Int]): Boolean =
      runOption(x).run == runOption(y).run
  }
  implicit val eqEffInt3: Eq[F[(Int, Int, Int)]] = new Eq[F[(Int, Int, Int)]] {
    def eqv(x: F[(Int, Int, Int)], y:F[(Int, Int, Int)]): Boolean =
      runOption(x).run == runOption(y).run
  }
  implicit def iso[R]: Isomorphisms[Eff[R, ?]] =
    Isomorphisms.invariant[Eff[R, ?]]


}
