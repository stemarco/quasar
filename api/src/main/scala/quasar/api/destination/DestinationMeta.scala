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

package quasar.api.destination

import quasar.Condition
import slamdata.Predef.{Exception, Option}

import monocle.macros.Lenses
import scalaz.Show
import scalaz.syntax.show._

@Lenses
final case class DestinationMeta(
    kind: DestinationType,
    name: DestinationName,
    status: Condition[Exception])

object DestinationMeta extends DestinationMetaInstances {
  def fromOption(
      kind: DestinationType,
      name: DestinationName,
      optErr: Option[Exception])
      : DestinationMeta =
    DestinationMeta(kind, name, Condition.optionIso.reverseGet(optErr))
}

sealed abstract class DestinationMetaInstances {
  implicit val show: Show[DestinationMeta] = {
    implicit val exShow: Show[Exception] =
      Show.shows(_.getMessage)

    Show.shows {
      case DestinationMeta(n, k, s) =>
        "DestinationMeta(" + k.shows + ", " + n.shows + ", " + s.shows + ")"
    }
  }
}
