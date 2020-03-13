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

package quasar.qsu

import quasar.contrib.scalaz.foldable._

import cats.{Eq, Foldable => CFoldable}
import cats.data.NonEmptyList
import scalaz.{Equal, Foldable, Tag, @@}

package object mra {
  type Dimensions[A] = NonEmptyList[A]
  type Region[A] = NonEmptyList[A]

  sealed trait AsSet
  val AsSet = Tag.of[AsSet]

  implicit def asSetEqual[F[_]: Foldable, A: Equal]: Equal[F[A] @@ AsSet] =
    Equal.equal((x, y) => Tag.unwrap(x).equalsAsSets(Tag.unwrap(y)))

  implicit def asSetEq[F[_]: CFoldable, A: Eq]: Eq[F[A] @@ AsSet] = {
    import shims.{eqToScalaz, foldableToScalaz}
    Eq.instance((x, y) => Tag.unwrap(x).equalsAsSets(Tag.unwrap(y)))
  }
}
