/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.api

import com.waz.model.{AssetId, ConvId, MessageId}
import com.waz.utils.Locales

case class ContentSearchQuery(originalString: String){
  import ContentSearchQuery._

  lazy val elements : Set[String] =
    originalString
      .split(" ")
      .map(transliterated)
      .filter(_.nonEmpty)
      .toSet

  override def toString = elements.reduceOption(_ + " " + _).getOrElse("")
  def toFtsQuery = elements.map("*" + _ + "*").reduceOption(_ + " " + _).getOrElse("")
}

object ContentSearchQuery{
  val empty = ContentSearchQuery("")

  def transliterated(s: String): String = Locales.transliteration("Latin-ASCII; Lower").transliterate(s).trim
}