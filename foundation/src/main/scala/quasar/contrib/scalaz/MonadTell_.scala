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

import slamdata.Predef.Unit

import scalaz._, Scalaz._

/** A version of MonadTell that doesn't extend Monad to avoid ambiguous implicits
  * in the presence of multiple "mtl" constraints.
  */
trait MonadTell_[F[_], W] {
  def writer[A](w: W, a: A): F[A]
  def tell(w: W): F[Unit] = writer(w, ())
}

object MonadTell_ extends MonadTell_Instances {
  def apply[F[_], W](implicit T: MonadTell_[F, W]): MonadTell_[F, W] = T

  def ignore[F[_], W](implicit F: Applicative[F]): MonadTell_[F, W] =
    new MonadTell_[F, W] {
      def writer[A](w: W, a: A) = a.point[F]
    }
}

sealed abstract class MonadTell_Instances extends MonadTell_Instances0 {
  implicit def eitherTMonadTell[F[_]: Functor, W, E](implicit T: MonadTell_[F, W]): MonadTell_[EitherT[F, E, ?], W] =
    new MonadTell_[EitherT[F, E, ?], W] {
      def writer[A](w: W, a: A) = EitherT(T.writer(w, a) map (_.right[E]))
    }

  implicit def writerTMonadTell[F[_]: Functor, W1, W2: Monoid](implicit T: MonadTell_[F, W1]): MonadTell_[WriterT[F, W2, ?], W1] =
    new MonadTell_[WriterT[F, W2, ?], W1] {
      def writer[A](w: W1, a: A) = WriterT(T.writer(w, a) strengthL mzero[W2])
    }

  implicit def writerMonadTell[W]: MonadTell_[Writer[W, ?], W] =
    new MonadTell_[Writer[W, ?], W] {
      def writer[A](w: W, a: A) = WriterT.writer((w, a))
    }

  implicit def stateTMonadTell[F[_]: Monad, W, S](implicit T: MonadTell_[F, W]): MonadTell_[StateT[F, S, ?], W] =
    new MonadTell_[StateT[F, S, ?], W] {
      def writer[A](w: W, a: A) = StateT(T.writer(w, a) strengthL _)
    }

  implicit def kleisliMonadTell[F[_], R, W](
      implicit F: MonadTell_[F, W]): MonadTell_[Kleisli[F, R, ?], W] =
    new MonadTell_[Kleisli[F, R, ?], W] {
      def writer[A](w: W, a: A): Kleisli[F, R, A] =
        Kleisli(_ => MonadTell_[F, W].writer(w, a))
    }

  /*
  // aspirationally, we would just have the following.  but it doesn't infer downstream
  implicit def hoistMonadTell[T[_[_], _]: Hoist, F[_]: Monad, W](
      implicit F: MonadTell_[F, W]): MonadTell_[T[F, ?], W] =
    new MonadTell_[T[F, ?], W] {
      def writer[A](w: W, a: A): T[F, A] = Hoist[T].liftM(MonadTell_[F, W].writer(w, a))
    }
   */
}

sealed abstract class MonadTell_Instances0 {
  implicit def monadTellNoMonad[F[_], W](implicit F: MonadTell[F, W]): MonadTell_[F, W] =
    new MonadTell_[F, W] {
      def writer[A](w: W, a: A) = F.writer(w, a)
    }
}

final class MonadTell_Ops[F[_], W, A] private[scalaz] (self: F[A])(implicit F: MonadTell_[F, W]) {
  final def :++>(w: ⇒ W)(implicit B: Bind[F]): F[A] =
    B.bind(self)(a => B.map(F.tell(w))(_ => a))

  final def :++>>(f: A ⇒ W)(implicit B: Bind[F]): F[A] =
    B.bind(self)(a => B.map(F.tell(f(a)))(_ => a))
}
