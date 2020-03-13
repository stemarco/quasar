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

package quasar.contrib.scalaz

import slamdata.Predef._

import scalaz._, Scalaz._

trait EitherTInstances extends EitherTInstances0 {
  implicit def eitherTCatchable[F[_]: Catchable : Functor, E]: Catchable[EitherT[F, E, ?]] =
    new Catchable[EitherT[F, E, ?]] {
      def attempt[A](fa: EitherT[F, E, A]) =
        EitherT[F, E, Throwable \/ A](
          Catchable[F].attempt(fa.run) map {
            case -\/(t)      => \/.right(\/.left(t))
            case \/-(-\/(e)) => \/.left(e)
            case \/-(\/-(a)) => \/.right(\/.right(a))
          })

      def fail[A](t: Throwable) =
        EitherT[F, E, A](Catchable[F].fail(t))
    }

  implicit def eitherTThrowableCatchable[M[_]: Monad]: Catchable[EitherT[M, Throwable, ?]] =
    new Catchable[EitherT[M, Throwable, ?]] {
      def attempt[A](f: EitherT[M, Throwable, A]): EitherT[M, Throwable, Throwable \/ A] =
        EitherT.rightT(f.run)
      def fail[A](err: Throwable): EitherT[M, Throwable, A] =
        EitherT.leftT(err.point[M])
    }

  implicit def eitherTMonadState[F[_], S, E](implicit F: MonadState[F, S]): MonadState[EitherT[F, E, ?], S] =
    new MonadState[EitherT[F, E, ?], S] {
      def init = F.init.liftM[EitherT[?[_], E, ?]]
      def get = F.get.liftM[EitherT[?[_], E, ?]]
      def put(s: S) = F.put(s).liftM[EitherT[?[_], E, ?]]
      override def map[A, B](fa: EitherT[F, E, A])(f: A => B) = fa map f
      def bind[A, B](fa: EitherT[F, E, A])(f: A => EitherT[F, E, B]) = fa flatMap f
      def point[A](a: => A) = F.point(a).liftM[EitherT[?[_], E, ?]]
    }
}

trait EitherTInstances0 extends EitherTInstances1 {
  implicit def eitherTMonadReader[F[_], R, E](implicit F: MonadReader[F, R]): MonadReader[EitherT[F, E, ?], R] =
    new MonadReader[EitherT[F, E, ?], R] {
      def ask = EitherT.rightT(F.ask)
      def local[A](f: R => R)(fa: EitherT[F, E, A]) = EitherT(F.local(f)(fa.run))
      override def map[A, B](fa: EitherT[F, E, A])(f: A => B) = fa map f
      def bind[A, B](fa: EitherT[F, E, A])(f: A => EitherT[F, E, B]) = fa flatMap f
      def point[A](a: => A) = F.point(a).liftM[EitherT[?[_], E, ?]]
    }
}

trait EitherTInstances1 extends EitherTInstances2 {
  implicit def eitherTMonadListen[F[_], W, E](implicit F: MonadListen[F, W]): MonadListen[EitherT[F, E, ?], W] =
    EitherT.monadListen[F, W, E]
}

trait EitherTInstances2 {
  implicit def eitherTMonadTell[F[_], W, E](implicit F: MonadTell[F, W]): MonadTell[EitherT[F, E, ?], W] =
    EitherT.monadTell[F, W, E]
}

object eitherT extends EitherTInstances {
  implicit class NestedEitherT[E,F[_]: Monad, A](a: EitherT[EitherT[F, E, ?], E, A]) {
    def flattenLeft: EitherT[F, E, A] =
      a.run.flatMapF(_.point[F])
  }

  def leftMapNT[F[_]: Functor, E0, E1](f: E0 => E1): EitherT[F, E0, ?] ~> EitherT[F, E1, ?] =
    λ[EitherT[F, E0, ?] ~> EitherT[F, E1, ?]](_.leftMap(f))
}
