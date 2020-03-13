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

package quasar.frontend.logicalplan

import slamdata.Predef._
import quasar.common.data.Data
import quasar.std.StdLib._

import matryoshka._
import matryoshka.implicits._

sealed abstract class JoinDir(val name: String) {
  import structural.MapProject

  val data: Data = Data.Str(name)

  def const[T](implicit T: Corecursive.Aux[T, LogicalPlan]): T =
    constant[T](data).embed

  def projectFrom[T](lp: T)(implicit T: Corecursive.Aux[T, LogicalPlan]): T =
    MapProject(lp, const).embed
}

object JoinDir {
  final case object Left extends JoinDir("left")
  final case object Right extends JoinDir("right")
}
