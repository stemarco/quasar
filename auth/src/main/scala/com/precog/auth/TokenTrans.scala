/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.auth

import blueeyes.json.JPath
import blueeyes.json.JsonAST._
import blueeyes.json.JsonDSL._
import blueeyes.json.JsonParser
import blueeyes.json.xschema.DefaultSerialization._

import java.io.FileReader

import com.precog.common._
import com.precog.common.security._

object TokenTrans {
  def main(args: Array[String]) {
    val input = args(0)

    val reader = new FileReader(input)
    try {
      val JArray(oldTokens) = JsonParser.parse(reader)

      val output = process(oldTokens)

      val tokens = output.keySet
      val grants = output.values.flatten

      for (token <- tokens) {
        println(compact(render(token.serialize(Token.UnsafeTokenDecomposer))))
      }
      println("############")
      for (grant <- grants) {
        println(compact(render(grant.serialize(Grant.UnsafeGrantDecomposer))))
      }
    } finally {
      reader.close()
    }
  }

  private def newUUID() = java.util.UUID.randomUUID.toString
  private def newGrantID(): String = (newUUID() + newUUID() + newUUID()).toLowerCase.replace("-","")

  def process(tokens: List[JValue]): Map[Token, Set[Grant]] = {
    tokens.foldLeft(Map.empty[Token, Set[Grant]]) { 
      case (acc, proto) => 
        val JString(uid) = (proto \ "uid") 
        val JString(path) = proto(JPath("permissions.path[0].pathSpec.subtree"))

        val writePermission = proto.find {
          case JString("PATH_WRITE") => true
          case _ => false
        }
        
        val writeGrants = Set( 
          Grant(newGrantID, Some("write_parent"), WritePermission(Path(path), None)),
          Grant(newGrantID, Some("owner_parent"), OwnerPermission(Path(path), None))
        )

        val readGrants = Set(
          Grant(newGrantID, Some("read_parent"), ReadPermission(Path(path), uid, None))
        )

        val grants = if (writePermission == JNothing) readGrants else readGrants ++ writeGrants

        val token = Token(uid, path.replaceAll("/", " "), grants.map{ _.gid }.toSet)        

        acc + (token -> grants)
    }
  }
}
