package org.atnos.eff
package syntax.addon.scalaz

import scalaz._
import org.atnos.eff.addon.scalaz._

object either extends org.atnos.eff.syntax.either with either

trait either {
  implicit final def toEitherEffectScalazOps[R, A](e: Eff[R, A]): EitherEffectScalazOps[R, A] = new EitherEffectScalazOps[R, A](e)
}

final class EitherEffectScalazOps[R, A](val e: Eff[R, A]) extends AnyVal {
  def runDisjunction[U, E](r: Eff[R, A])(implicit m: Member.Aux[(E Either ?), R, U]): Eff[U, E \/ A] =
    addon.scalaz.either.runDisjunction(e)

  def runDisjunctionCombine[U, E](r: Eff[R, A])(implicit m: Member.Aux[(E Either ?), R, U], s: Semigroup[E]): Eff[U, E \/ A] =
    addon.scalaz.either.runDisjunctionCombine(r)

  def catchLeftCombine[E](r: Eff[R, A])(handle: E => Eff[R, A])(implicit member: (E Either ?) /= R, s: Semigroup[E]): Eff[R, A] =
    addon.scalaz.either.catchLeftCombine(r)(handle)

}
