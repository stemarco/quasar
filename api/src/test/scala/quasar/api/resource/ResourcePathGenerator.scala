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

package quasar.api.resource

import quasar.contrib.pathy.AFile
import quasar.pkg.tests._

import pathy.scalacheck.PathyArbitrary._

trait ResourcePathGenerator {
  implicit val resourcePathArbitrary: Arbitrary[ResourcePath] =
    Arbitrary(for {
      n <- choose(1, 10)
      p <- if (n > 2) arbitrary[AFile].map(ResourcePath.leaf(_))
           else const(ResourcePath.root())
    } yield p)
}

object ResourcePathGenerator extends ResourcePathGenerator
