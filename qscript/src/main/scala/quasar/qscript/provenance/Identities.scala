/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.qscript.provenance

import slamdata.Predef._

import quasar.{NonTerminal, RenderTree, RenderedTree, Terminal}
import quasar.RenderTree.ops._
import quasar.contrib.cats.stateT._
import quasar.contrib.scalaz.MonadState_

import scala.collection.immutable.{TreeMap, TreeSet, SortedMap, SortedSet}
import scala.math.max

import monocle.{Lens, Optional, Prism}

import cats.{Eq, Eval, Foldable, Order, Show}
import cats.data.{EitherT, NonEmptyList, StateT}
import cats.instances.int._
import cats.instances.list._
import cats.instances.set._
import cats.instances.sortedSet._
import cats.instances.tuple._
import cats.kernel.BoundedSemilattice
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.reducible._
import cats.syntax.show._

import scalaz.\&/, \&/._
import shims._

/** A set of vectors where new items may be added to, or conjoined with, the end
  * of all vectors.
  */
final class Identities[A] private (
    protected val nextV: Int,
    protected val roots: Set[Int],
    protected val ends: Set[Int],
    // vertex -> (node, edges out, edges in)
    protected val g: Identities.G[A]) {

    import Identities.{G => IG, MergeState, Node, Vert => IVert}

  /** Apply a function to the corresponding levels of two `Identities`, where a
    * level is defined as the values of every component vector at a particular
    * index, starting from 0.
    */
  def alignLevelsWith[B, C](that: Identities[B])(f: NonEmptyList[A] \&/ NonEmptyList[B] => C)
      : List[C] = {

    def vvalue[X](i: Int, m: Identities.G[X]): X =
      m(i).node.value

    @tailrec
    def align0(thisLvl: Set[Int], thatLvl: Set[Int], acc: List[C]): List[C] = {
      val these = (thisLvl.toList.toNel, thatLvl.toList.toNel) match {
        case (Some(thiss), Some(thats)) =>
          Some(Both(thiss.map(vvalue(_, g)), thats.map(vvalue(_, that.g))))

        case (Some(thiss), None) =>
          Some(This(thiss.map(vvalue(_, g))))

        case (None, Some(thats)) =>
          Some(That(thats.map(vvalue(_, that.g))))

        case (None, None) =>
          None
      }

      these match {
        case Some(t) =>
          align0(
            thisLvl.unorderedFoldMap(vout(_).get(g)),
            thatLvl.unorderedFoldMap(vout(_).get(that.g)),
            f(t) :: acc)

        case None =>
          acc
      }
    }

    align0(roots, that.roots, Nil).reverse
  }

  /** The number of vectors in the set. */
  def breadth: Int = {
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def go(lvl: Set[Int]): Eval[Int] =
      Eval.always(lvl.toList) flatMap { vs =>
        vs foldMapM { v =>
          val ins = vin(v).get(g)

          if (ins.isEmpty)
            Eval.now(1)
          else
            go(ins)
        }
      }

    go(ends).value
  }

  /** Conjoin a value with the end of the vectors. */
  def conj(a: A): Identities[A] =
    add(Node.conj(a))

  /** Alias for `conj`. */
  def :≻ (a: A): Identities[A] =
    conj(a)

  /** The length of the longest vector in the set. */
  def depth: Int = {
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def go(lvl: Set[Int], d: Int): Eval[Int] =
      Eval.always(lvl.toList) flatMap { vs =>
        vs.foldLeftM(d) { (acc, v) =>
          val vt = g(v)
          val d1 = if (nsnoc.nonEmpty(vt)) d + 1 else d

          val nextd =
            if (vt.out.isEmpty)
              Eval.now(d1)
            else
              go(vt.out, d1)

          nextd.map(max(_, acc))
        }
      }

    go(roots, 0).value
  }

  /** A view of these identities as a set of lists of conjoined regions. */
  def expanded: List[NonEmptyList[NonEmptyList[A]]] = {
    def updateVecs(xs: List[NonEmptyList[NonEmptyList[A]]], conj: Boolean, a: A)
        : List[NonEmptyList[NonEmptyList[A]]] =
      if (xs.isEmpty)
        List(NonEmptyList.one(NonEmptyList.one(a)))
      else if (conj)
        xs.map(s => NonEmptyList(a :: s.head, s.tail))
      else
        xs.map(NonEmptyList.one(a) :: _)

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def expand(vs: Set[Int], conj: Boolean, xs: List[NonEmptyList[NonEmptyList[A]]])
        : Eval[List[NonEmptyList[NonEmptyList[A]]]] =
      Eval.always(NonEmptyList.fromList(vs.toList)) flatMap {
        case Some(nel) =>
          nel reduceMapM { v =>
            val IVert(n, _, i) = g(v)
            expand(i, Node.conj.nonEmpty(n), updateVecs(xs, conj, n.value))
          }

        case None =>
          Eval.now(xs)
      }

    expand(ends, false, Nil).value
  }

  /** Returns all but the last conjoined region of each vector. */
  def init: Option[Identities[A]] = {
    @tailrec
    def go(toDrop: Set[Int], acce: Set[Int], accg: G): (Set[Int], G) = {
      val (toDrop1, acce1, accg1) = toDrop.foldLeft((Set[Int](), acce, accg)) {
        case ((td, ae, ag), v) =>
          val IVert(n, _, i) = ag(v)

          val (nexttd, nextae) =
            if (Node.snoc.nonEmpty(n))
              (td, ae ++ i)
            else
              (td ++ i, ae)

          val nextg = i.foldLeft(ag) {
            case (mg, iv) => vout(iv).modify(_ - v)(mg)
          }

          (nexttd, nextae, nextg)
      }

      if (toDrop1.isEmpty)
        (acce1, accg1)
      else
        go(toDrop1, acce1, accg1)
    }

    if (nonEmpty) {
      val (ends1, g1) = go(ends, Set(), g)
      val singles = roots & ends
      val gone = (singles &~ ends1).filter(v => vout(v).exist(_.isEmpty)(g))
      Some(new Identities(nextV, roots -- gone, ends1, g1))
    } else {
      None
    }
  }

  def isEmpty: Boolean =
    roots.isEmpty

  /** Merge with another set of identities. */
  def merge(that: Identities[A])(implicit A: Order[A])
      : Identities[A] = {

    type F[X] = StateT[Eval, MergeState[A], X]

    val S = MonadState_[F, MergeState[A]]
    val zmap = TreeMap.empty[Node[A], NonEmptyList[Int]](Order[Node[A]].toOrdering)

    def nodeMap(vs: Set[Int], m: G): SortedMap[Node[A], NonEmptyList[Int]] =
      vs.foldLeft(zmap) { (ns, v) =>
        val n = vnode(v).get(m)
        ns.updated(n, ns.get(n).fold(NonEmptyList.one(v))(v :: _))
      }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def mergeLvl(thisLvl: Set[Int], thatLvl: Set[Int], lvl: Int, rg: G): F[G] =
      if (thatLvl.isEmpty)
        rg.pure[F]
      else
        for {
          _ <- S.modify(MergeState.lvlSnap[A].modify(_.updated(lvl, (thisLvl, thatLvl, rg))))

          thisNodes = nodeMap(thisLvl, g)

          res <- (thatLvl.toList.foldLeftM(rg) {
            case (ng, thatV) => EitherT(mergeThat(thisNodes, lvl, ng, thatV))
          }).value

          resultg <- res match {
            case Left(restartFrom) =>
              S.gets(_.lvlSnap(restartFrom)) flatMap {
                case (thiss, thats, lg) => mergeLvl(thiss, thats, restartFrom, lg)
              }

            case Right(ng) =>
              val nextThis = thisLvl.unorderedFoldMap(vout(_).get(g))
              val nextThat = thatLvl.unorderedFoldMap(vout(_).get(that.g))
              mergeLvl(nextThis, nextThat, lvl + 1, ng)
          }
        } yield resultg

    // `thatV` represents a divergence, so check if there is a converged
    // region that doesn't start at the root
    def backtrackRequired(thatV: Int, s: MergeState[A]): Boolean =
      s.convergedRegions.get(thatV).exists(_._1 > 0)

    def evenOrSubsumed(thisV: Int, thatV: Int, s: MergeState[A]): Boolean =
      that.roots(thatV) ||
      !(ends(thisV) ^ that.ends(thatV)) ||
      s.convergedRegions.get(thatV).exists(_._1 === 0)

    def mergeThat(
        thisNodes: SortedMap[Node[A], NonEmptyList[Int]],
        lvl: Int,
        ing: G,
        thatV: Int)
        : F[Either[Int, G]] =
      for {
        s <- S.get

        thatN = vnode(thatV).get(that.g)

        mergeCandidates = thisNodes.get(thatN) map { thisVs =>
          val valids = thisVs.filterNot(v => s.invalidMerges(v -> thatV))

          s.convergedRegions.get(thatV).fold(valids) {
            case (_, merges) =>
              val thisMs = merges.map(_._1)
              valids.filter(v => vin(v).get(g).intersect(thisMs).nonEmpty)
          }
        }

        result <- mergeCandidates match {
          // Found candidates for merge
          case Some(thisV :: _) if evenOrSubsumed(thisV, thatV, s) =>
            val updateConverged = { s: MergeState[A] =>
              val cstate =
                s.convergedRegions
                  .get(thatV)
                  .fold((lvl, Set(thisV -> thatV)))(_.map(_ + (thisV -> thatV)))

              val toUpd = vout(thatV).get(that.g) + thatV

              MergeState.convergedRegions[A]
                .modify(rs => toUpd.foldLeft(rs)(_.updated(_, cstate)))(s)
            }

            val updateRemap =
              MergeState.remap[A].modify(_ + (thatV -> thisV))

            val remappedIn = vin(thatV).get(that.g).map(s.remap)

            val nextg = remappedIn.foldLeft(vin(thisV).modify(_ ++ remappedIn)(ing)) {
              case (ng, v) => vout(v).modify(_ + thisV)(ng)
            }

            S.put((updateConverged andThen updateRemap)(s)).as(Right(nextg))

          // Diverged and need to backtrack
          case _ if backtrackRequired(thatV, s) =>
            val (backLvl, badMerges) = s.convergedRegions(thatV)

            val clearConverged =
              MergeState.convergedRegions[A] modify { m =>
                badMerges.foldLeft(m - thatV) {
                  case (m1, (_, v)) => m1 - v
                }
              }

            val updateInvalid =
              MergeState.invalidMerges[A].modify(_ ++ badMerges)

            S.put((clearConverged andThen updateInvalid)(s)).as(Left(backLvl))

          // Diverged, add a new entry for thatV
          case _ =>
            val remappedIn = vin(thatV).get(that.g).map(s.remap)

            val nextg = remappedIn.foldLeft(ing.updated(s.nextV, IVert(thatN, Set[Int](), remappedIn))) {
              case (ng, v) => vout(v).modify(_ + s.nextV)(ng)
            }

            val incNextV =
              MergeState.nextV[A].modify(_ + 1)

            val updateRemap =
              MergeState.remap[A].modify(_ + (thatV -> s.nextV))

            val unsetConverged =
              MergeState.convergedRegions[A].modify(_ - thatV)

            S.put((incNextV andThen updateRemap andThen unsetConverged)(s))
              .as(Right(nextg))
        }
      } yield result

    val (s, mergedG) =
      mergeLvl(roots, that.roots, 0, g)
        .run(MergeState.init[A](nextV))
        .value

    new Identities(
      s.nextV,
      roots ++ that.roots.map(s.remap),
      ends ++ that.ends.map(s.remap),
      mergedG)
  }

  def nonEmpty: Boolean =
    !isEmpty

  /** Append a value to all vectors. */
  def snoc(a: A): Identities[A] =
    add(Node.snoc(a))

  /** Alias for `snoc`. */
  def :+ (a: A): Identities[A] =
    snoc(a)

  /** The internal size of the representation. */
  def storageSize: Int = {
    @tailrec
    def go(lvl: Set[Int], ct: Int): Int =
      if (lvl.isEmpty)
        ct
      else
        go(lvl.unorderedFoldMap(vout(_).get(g)), lvl.size + ct)

    go(roots, 0)
  }

  /** Add a value just before the end of each vector.
    *
    * If the end of the vector is conjoined, the value will be added just before
    * the conjoined region.
    *
    * TODO: Plenty of room for an optimized impl, currently O(n) can probably
    *       be reduced to O(distinct ends) and omit the `Order` instance.
    */
  def submerge(a: A)(implicit A: Order[A]): Identities[A] =
    Identities.contracted(expanded.map(_.reverse match {
      case NonEmptyList(l, i) => NonEmptyList(l, NonEmptyList.one(a) :: i).reverse
    }))

  /** Returns whether `this` is equal to `that`. */
  def === (that: Identities[A])(implicit A: Order[A]): Boolean = {
    type E = (Node[A], Node[A])

    val zset = TreeSet.empty[E](Order[E].toOrdering)

    def edgesAndNext(lvl: Set[Int], gg: G): (SortedSet[(Node[A], Node[A])], Set[Int]) =
      lvl.foldLeft((zset, Set[Int]())) {
        case ((edges, nxt), v) =>
          val vt = gg(v)

          val edges1 = vt.out.foldLeft(edges) { (es, o) =>
            es + (vt.node -> vnode(o).get(gg))
          }

          (edges1, nxt ++ vt.out)
      }

    @tailrec
    def levelsEqual(thislvl: Set[Int], thatlvl: Set[Int]): Boolean =
      if (thislvl.isEmpty && thatlvl.isEmpty) {
        true
      } else if (thislvl.size =!= thatlvl.size) {
        false
      } else {
        val (thisEdges, thisNext) = edgesAndNext(thislvl, g)
        val (thatEdges, thatNext) = edgesAndNext(thatlvl, that.g)
        (thisEdges === thatEdges) && levelsEqual(thisNext, thatNext)
      }

    def nodes(lvl: Set[Int], gg: G): SortedSet[Node[A]] =
      lvl.foldLeft(TreeSet.empty[Node[A]](Order[Node[A]].toOrdering)) { (ns, v) =>
        ns + vnode(v).get(gg)
      }

    if (roots === ends && that.roots === that.ends)
      nodes(roots, g) === nodes(that.roots, that.g)
    else
      levelsEqual(roots, that.roots)
  }

  override def toString: String = {
    implicit val showA = Show.fromToString[A]
    this.show
  }

  ////

  private type Vert = IVert[A]
  private type G = IG[A]

  private val nsnoc: Optional[Vert, A] =
    IVert.node composePrism Node.snoc

  private def vert[X](i: Int): Lens[IG[X], IVert[X]] =
    Lens((_: Map[Int, IVert[X]])(i))(v => _.updated(i, v))

  private def vnode[X](i: Int): Lens[IG[X], Node[X]] =
    vert(i) composeLens IVert.node

  private def vout[X](i: Int): Lens[IG[X], Set[Int]] =
    vert(i) composeLens IVert.out

  private def vin[X](i: Int): Lens[IG[X], Set[Int]] =
    vert(i) composeLens IVert.in[X]

  private def add(node: Node[A]): Identities[A] = {
    val ve = Set(nextV)

    if (isEmpty)
      new Identities(
        nextV + 1,
        ve,
        ve,
        g.updated(nextV, IVert(node, Set(), Set())))
    else
      new Identities(
        nextV + 1,
        roots,
        ve,
        ends
          .foldLeft(g)((g1, i) => vout(i).set(ve)(g1))
          .updated(nextV, IVert(node, Set(), ends)))
  }
}

object Identities extends IdentitiesInstances {
  def apply[A](as: A*): Identities[A] =
    fromFoldable(as.toList)

  def contracted[F[_]: Foldable, A: Order](exp: F[NonEmptyList[NonEmptyList[A]]])
      : Identities[A] = {

    def addRegion(ids: Identities[A], r: NonEmptyList[A]): Identities[A] =
      r.reduceLeftTo(ids :+ _)(_ :≻ _)

    def single(as: NonEmptyList[NonEmptyList[A]]): Identities[A] =
      as.reduceLeftTo(addRegion(empty[A], _))(addRegion(_, _))

    exp.reduceLeftToOption(single)((ids, r) => ids.merge(single(r)))
      .getOrElse(empty[A])
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def empty[A]: Identities[A] =
    empty_.asInstanceOf[Identities[A]]

  def fromFoldable[F[_]: Foldable, A](fa: F[A]): Identities[A] =
    fa.foldLeft(empty[A])(_ :+ _)

  ////

  private type G[A] = Map[Int, Vert[A]]

  private[this] val empty_ : Identities[Nothing] =
    new Identities(0, Set(), Set(), Map())

  private final case class MergeState[A](
      nextV: Int,
      // thatV -> thisV
      remap: Map[Int, Int],
      // Set[(thisV, thatV)]
      invalidMerges: Set[(Int, Int)],
      // thatV -> (level, merges)
      convergedRegions: Map[Int, (Int, Set[(Int, Int)])],
      // level -> (thisVs, thatVs, G)
      lvlSnap: Map[Int, (Set[Int], Set[Int], G[A])])

  private object MergeState {
    def init[A](nextV: Int): MergeState[A] =
      MergeState(nextV, Map(), Set(), Map(), Map())

    def nextV[A]: Lens[MergeState[A], Int] =
      Lens((_: MergeState[A]).nextV)(v => _.copy(nextV = v))

    def remap[A]: Lens[MergeState[A], Map[Int, Int]] =
      Lens((_: MergeState[A]).remap)(v => _.copy(remap = v))

    def invalidMerges[A]: Lens[MergeState[A], Set[(Int, Int)]] =
      Lens((_: MergeState[A]).invalidMerges)(v => _.copy(invalidMerges = v))

    def convergedRegions[A]: Lens[MergeState[A], Map[Int, (Int, Set[(Int, Int)])]] =
      Lens((_: MergeState[A]).convergedRegions)(v => _.copy(convergedRegions = v))

    def lvlSnap[A]: Lens[MergeState[A], Map[Int, (Set[Int], Set[Int], G[A])]] =
      Lens((_: MergeState[A]).lvlSnap)(v => _.copy(lvlSnap = v))
  }

  protected sealed trait Node[A] extends Product with Serializable {
    def value: A
  }

  protected object Node extends NodeInstances0 {
    final case class Conj[A](value: A) extends Node[A]
    final case class Snoc[A](value: A) extends Node[A]

    def conj[A]: Prism[Node[A], A] =
      Prism.partial[Node[A], A] {
        case Conj(a) => a
      } (Conj(_))

    def snoc[A]: Prism[Node[A], A] =
      Prism.partial[Node[A], A] {
        case Snoc(a) => a
      } (Snoc(_))

    def value[A]: Lens[Node[A], A] =
      Lens[Node[A], A](_.value) { a => {
        case Conj(_) => Conj(a)
        case Snoc(_) => Snoc(a)
      }}

    implicit def order[A: Order]: Order[Node[A]] =
      new Order[Node[A]] {
        def compare(x: Node[A], y: Node[A]): Int =
          (x, y) match {
            case (Conj(a), Conj(b)) => Order[A].compare(a, b)
            case (Conj(_), Snoc(_)) => -1
            case (Snoc(a), Snoc(b)) => Order[A].compare(a, b)
            case (Snoc(_), Conj(_)) => 1
          }
      }

    implicit def show[A: Show]: Show[Node[A]] =
      Show.show {
        case Conj(a) => s"Conj(${a.show})"
        case Snoc(a) => s"Snoc(${a.show})"
      }
  }

  protected abstract class NodeInstances0 {
    implicit def eqv[A: Eq]: Eq[Node[A]] =
      new Eq[Node[A]] {
        def eqv(x: Node[A], y: Node[A]): Boolean =
          (x, y) match {
            case (Node.Conj(a), Node.Conj(b)) => Eq[A].eqv(a, b)
            case (Node.Snoc(a), Node.Snoc(b)) => Eq[A].eqv(a, b)
            case _ => false
          }
      }
  }

  protected final case class Vert[A](node: Node[A], out: Set[Int], in: Set[Int])

  protected object Vert {
    def node[A]: Lens[Vert[A], Node[A]] =
      Lens((_: Vert[A]).node)(n => _.copy(node = n))

    def out[A]: Lens[Vert[A], Set[Int]] =
      Lens((_: Vert[A]).out)(o => _.copy(out = o))

    def in[A]: Lens[Vert[A], Set[Int]] =
      Lens((_: Vert[A]).in)(i => _.copy(in = i))
  }
}

sealed abstract class IdentitiesInstances {
  implicit def eqv[A: Order]: Eq[Identities[A]] =
    Eq.instance(_ === _)

  implicit def boundedSemilattice[A: Order]: BoundedSemilattice[Identities[A]] =
    new BoundedSemilattice[Identities[A]] {
      val empty = Identities.empty[A]

      def combine(x: Identities[A], y: Identities[A]) =
        x merge y
    }

  implicit def renderTree[A: Show]: RenderTree[Identities[A]] = {
    def showVector(v: NonEmptyList[NonEmptyList[A]]): String =
      v.toList.iterator
        .map(_.toList.iterator.map(_.show).mkString(" :≻ "))
        .mkString("<", ", ", ">")

    RenderTree make { ids =>
      val sortedExp = ids.expanded.sortBy(Nel2.size(_))

      NonTerminal(List("Identities"), None, sortedExp map { v =>
        Terminal(Nil, Some(showVector(v)))
      })
    }
  }

  implicit def show[A: Show]: Show[Identities[A]] =
    Show.show(ids => scalaz.Show[RenderedTree].shows(ids.render))

  private val Nel2 = Foldable[NonEmptyList].compose[NonEmptyList]
}
