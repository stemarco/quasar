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

package quasar.contrib.std

import java.util.UUID

import scalaz.{Order, Ordering, Show}

object uuid {

  implicit val uuidOrder: Order[UUID] = new Order[UUID] {
    def order(x: UUID, y: UUID): Ordering =
      Ordering.fromInt(x.compareTo(y))
  }

  implicit val showUUID: Show[UUID] = Show.showFromToString
}
