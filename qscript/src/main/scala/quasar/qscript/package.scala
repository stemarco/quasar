/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar

import slamdata.Predef._
import quasar.api.resource.ResourcePath
import quasar.contrib.iota._
import quasar.contrib.iota.SubInject
import quasar.contrib.scalaz.MonadError_
import quasar.fp._

import matryoshka._
import matryoshka.data._
import matryoshka.patterns._
import scalaz._, Scalaz._
import iotaz.{CopK, TNilK}
import iotaz.TListK.:::

/** The various representations of an arbitrary query, as seen by the filesystem
  * connectors, along with the operations for dealing with them.
  *
  * There are a few patterns that are worth noting:
  * - `(src: A, ..., lBranch: FreeQS[T], rBranch: FreeQS[T], ...)` – used in
  *   operations that combine multiple data sources (notably joins and unions).
  *   This holds the divergent parts of the data sources in the branches, with
  *   [[SrcHole]] indicating a reference back to the common `src` of the two
  *   branches. There is not required to be a [[SrcHole]].
  * - `Free[F, A]` – we use this structure as a restricted form of variable
  *   binding, where `F` is some pattern functor, and `A` is some enumeration
  *   that has a specific referent. E.g., [[FreeMap]] is a recursive structure
  *   of [[MapFunc]] that has a single “variable”, [[SrcHole]], which (usually)
  *   refers to the `src` parameter of that operation. [[JoinFunc]], [[FreeQS]],
  *   and the `repair` parameter to [[Reduce]] behave similarly.
  * - We use the type parameter `QS[_]` to indicate QScript, as well as the type
  *   parameters `IN[_]` and `OUT[_]` to indicate the input and output
  *   coproducts in transformations where they can be different.
  */
// NB: Here we no longer care about provenance. Backends can’t do anything with
//     it, so we simply represent joins and crosses directly. This also means
//     that we don’t need to model certain things – project_d is just a
//     data-level function, nest_d & swap_d only modify provenance and so are
//     irrelevant here, and autojoin_d has been replaced with a lower-level join
//     operation that doesn’t include the cross portion.
package object qscript {

  /** The key that wraps the shifted RValues in the evaluation of
   *  an InterpretedRead.
   */
  val ShiftedKey: String = "shifted"

  type MonadPlannerErr[F[_]] = MonadError_[F, PlannerError]
  def MonadPlannerErr[F[_]](implicit ev: MonadPlannerErr[F]): MonadPlannerErr[F] = ev

  /** This type is _only_ used for join branch-like structures. It’s an
    * unfortunate consequence of not having mutually-recursive data structures.
    * Once we do, this can go away. It should _not_ be used in other situations.
    */
  type QScriptTotal[T[_[_]], A] = CopK[
         QScriptCore[T, ?]
     ::: ThetaJoin[T, ?]
     ::: EquiJoin[T, ?]
     ::: Const[InterpretedRead[ResourcePath], ?]
     ::: Const[Read[ResourcePath], ?]
     ::: TNilK, A]

  object QCT {
    def apply[T[_[_]], A](qc: QScriptCore[T, A]): QScriptTotal[T, A] =
      CopK.Inject[QScriptCore[T, ?], QScriptTotal[T, ?]].inj(qc)

    def unapply[T[_[_]], A](qt: QScriptTotal[T, A]): Option[QScriptCore[T, A]] =
      CopK.Inject[QScriptCore[T, ?], QScriptTotal[T, ?]].prj(qt)
  }

  /** Initial QScript. */
  type QScriptEducated[T[_[_]], A] =
    CopK[QScriptCore[T, ?] ::: ThetaJoin[T, ?] ::: Const[Read[ResourcePath], ?] ::: TNilK, A]

  implicit def educatedToTotal[T[_[_]]]: Injectable[QScriptEducated[T, ?], QScriptTotal[T, ?]] =
    SubInject[QScriptEducated[T, ?], QScriptTotal[T, ?]]

  object QCE {
    def apply[T[_[_]], A](qc: QScriptCore[T, A]): QScriptEducated[T, A] =
      CopK.Inject[QScriptCore[T, ?], QScriptEducated[T, ?]].inj(qc)

    def unapply[T[_[_]], A](qt: QScriptEducated[T, A]): Option[QScriptCore[T, A]] =
      CopK.Inject[QScriptCore[T, ?], QScriptEducated[T, ?]].prj(qt)
  }

  type QScriptNormalizedList[T[_[_]]] =
    QScriptCore[T, ?] ::: EquiJoin[T, ?] ::: Const[Read[ResourcePath], ?] ::: TNilK

  type QScriptNormalized[T[_[_]], A] =
    CopK[QScriptNormalizedList[T], A]

  type MapFunc[T[_[_]], A] = CopK[MapFuncCore[T, ?] ::: MapFuncDerived[T, ?] ::: TNilK, A]

  object MFC {
    def apply[T[_[_]], A](mfc: MapFuncCore[T, A]): MapFunc[T, A] =
      CopK.Inject[MapFuncCore[T, ?], MapFunc[T, ?]].inj(mfc)

    def unapply[T[_[_]], A](mf: MapFunc[T, A]): Option[MapFuncCore[T, A]] =
      CopK.Inject[MapFuncCore[T, ?], MapFunc[T, ?]].prj(mf)
  }

  object MFD {
    def apply[T[_[_]], A](mfc: MapFuncDerived[T, A]): MapFunc[T, A] =
      CopK.Inject[MapFuncDerived[T, ?], MapFunc[T, ?]].inj(mfc)

    def unapply[T[_[_]], A](mf: MapFunc[T, A]): Option[MapFuncDerived[T, A]] =
      CopK.Inject[MapFuncDerived[T, ?], MapFunc[T, ?]].prj(mf)
  }

  type RecFreeMapA[T[_[_]], A] = Free[RecFreeS[MapFunc[T, ?], ?], A]
  type RecFreeMap[T[_[_]]]     = RecFreeMapA[T, Hole]

  type FreeQS[T[_[_]]]      = Free[QScriptTotal[T, ?], Hole]
  type FreeMapA[T[_[_]], A] = Free[MapFunc[T, ?], A]
  type FreeMap[T[_[_]]]     = FreeMapA[T, Hole]
  type JoinFunc[T[_[_]]]    = FreeMapA[T, JoinSide]

  type CoEnvQS[T[_[_]], A]      = CoEnv[Hole, QScriptTotal[T, ?], A]
  type CoEnvMapA[T[_[_]], A, B] = CoEnv[A, MapFunc[T, ?], B]
  type CoEnvMap[T[_[_]], A]     = CoEnvMapA[T, Hole, A]

  object ExtractFunc {
    def unapply[T[_[_]], A](fma: FreeMapA[T, A]): Option[MapFuncCore[T, FreeMapA[T, A]]] = fma match {
      case Embed(CoEnv(\/-(MFC(func)))) => Some(func)
      case _ => None
    }
  }

  object ExtractFuncDerived {
    def unapply[T[_[_]], A](fma: FreeMapA[T, A]): Option[MapFuncDerived[T, FreeMapA[T, A]]] = fma match {
      case Embed(CoEnv(\/-(MFD(func)))) => Some(func)
      case _ => None
    }
  }

  def HoleF[T[_[_]]]: FreeMap[T] = Free.point[MapFunc[T, ?], Hole](SrcHole)
  def HoleR[T[_[_]]]: RecFreeMap[T] = Free.point[RecFreeS[MapFunc[T, ?], ?], Hole](SrcHole)
  def HoleQS[T[_[_]]]: FreeQS[T] = Free.point[QScriptTotal[T, ?], Hole](SrcHole)
  def LeftSideF[T[_[_]]]: JoinFunc[T] = Free.point[MapFunc[T, ?], JoinSide](LeftSide)
  def RightSideF[T[_[_]]]: JoinFunc[T] = Free.point[MapFunc[T, ?], JoinSide](RightSide)
  def ReduceIndexF[T[_[_]]](i: Int \/ Int): FreeMapA[T, ReduceIndex] = Free.point[MapFunc[T, ?], ReduceIndex](ReduceIndex(i))

  def rebase[M[_]: Bind, A](in: M[A], key: M[A]): M[A] = in >> key

  /** A variant of `repeatedly` that works with `Inject` instances. */
  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def injectRepeatedly[F [_], G[a] <: ACopK[a], A](op: F[A] => Option[G[A]])(implicit F: F :<<: G): F[A] => G[A] =
    fa => op(fa).fold(F.inj(fa))(ga => F.prj(ga).fold(ga)(injectRepeatedly(op)))
}
