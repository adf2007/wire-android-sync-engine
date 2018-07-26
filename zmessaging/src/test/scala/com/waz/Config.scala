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
package com.waz
import com.waz.api.EmailCredentials
import com.waz.content.AccountStorage
import com.waz.model.AccountData.Password
import com.waz.model.{AccountData, EmailAddress, UserId}
import com.waz.sync.client.{AuthenticationManager, LoginClient, LoginClientImpl}
import com.waz.utils.Managed
import com.waz.utils.events.{EventStream, Signal}
import com.waz.utils.wrappers.DB
import com.waz.znet2.http.HttpClient
import com.waz.znet2.http.Request.UrlCreator
import com.waz.znet2.{AuthRequestInterceptor, HttpClientOkHttpImpl}

import scala.collection.generic.CanBuild
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object Config {

  val testEmailCredentials = EmailCredentials(EmailAddress("mykhailo+5@wire.com"), Password("123456789"))

  val BackendUrl: String = "https://staging-nginz-https.zinfra.io"

  implicit val HttpClient: HttpClient = HttpClientOkHttpImpl(enableLogging = true)
  implicit val urlCreator: UrlCreator = UrlCreator.simpleAppender(BackendUrl)

  private val trackingService = new DisabledTrackingService

  private val InMemoryAccountStorage = new AccountStorage {

    var testUserData: Option[AccountData] = None

    override def onAdded: EventStream[Seq[AccountData]]                               = ???
    override def onUpdated: EventStream[Seq[(AccountData, AccountData)]]              = ???
    override def onDeleted: EventStream[Seq[UserId]]                                  = ???
    override def onChanged: EventStream[Seq[AccountData]]                             = ???
    override protected def load(key: UserId)(implicit db: DB): Option[AccountData]    = ???
    override protected def load(keys: Set[UserId])(implicit db: DB): Seq[AccountData] = ???
    override protected def save(values: Seq[AccountData])(implicit db: DB): Unit      = ???
    override protected def delete(keys: Iterable[UserId])(implicit db: DB): Unit      = ???
    override protected def updateInternal(key: UserId, updater: AccountData => AccountData)(current: AccountData): Future[Option[(AccountData, AccountData)]] = ???
    override def find[A, B](predicate: AccountData => Boolean,
                            search: DB => Managed[TraversableOnce[AccountData]],
                            mapping: AccountData => A)
                           (implicit cb: CanBuild[A, B]): Future[B] = ???
    override def filterCached(f: AccountData => Boolean): Future[Vector[AccountData]]          = ???
    override def foreachCached(f: AccountData => Unit): Future[Unit]                           = ???
    override def deleteCached(predicate: AccountData => Boolean): Future[Unit] = ???
    override def onChanged(key: UserId): EventStream[AccountData] = ???
    override def onRemoved(key: UserId): EventStream[UserId] = ???
    override def optSignal(key: UserId): Signal[Option[AccountData]] = ???
    override def signal(key: UserId): Signal[AccountData] = ???
    override def insert(v: AccountData): Future[AccountData] = {
      testUserData = Some(v)
      Future.successful(v)
    }
    override def insertAll(vs: Traversable[AccountData]): Future[Set[AccountData]] = ???
    override def get(key: UserId): Future[Option[AccountData]] = {
      Future.successful(testUserData.filter(_.id == key))
    }
    override def getOrCreate(key: UserId, creator: => AccountData): Future[AccountData] = ???
    override def list(): Future[Seq[AccountData]] = ???
    override def getAll(keys: Traversable[UserId]): Future[Seq[Option[AccountData]]] = ???
    override def update(key: UserId, updater: AccountData => AccountData): Future[Option[(AccountData, AccountData)]] =
      Future.successful {
        testUserData.map { data =>
          val updated = updater(data)
          testUserData = Some(updated)
          data -> updated
        }
      }
    override def updateAll(updaters: collection.Map[UserId, AccountData => AccountData]): Future[Seq[(AccountData, AccountData)]] = ???
    override def updateAll2(keys: Iterable[UserId], updater: AccountData => AccountData): Future[Seq[(AccountData, AccountData)]] = ???
    override def updateOrCreate(key: UserId, updater: AccountData => AccountData, creator: => AccountData): Future[AccountData] = ???
    override def updateOrCreateAll(updaters: Map[UserId, Option[AccountData] => AccountData]): Future[Set[AccountData]] = ???
    override def updateOrCreateAll2(keys: Iterable[UserId], updater: (UserId, Option[AccountData]) => AccountData): Future[Set[AccountData]] = ???
    override def put(key: UserId, value: AccountData): Future[AccountData] = ???
    override def getRawCached(key: UserId): Option[AccountData] = ???
    override def remove(key: UserId): Future[Unit] = {
      testUserData = None
      Future.successful(())
    }
    override def removeAll(keys: Iterable[UserId]): Future[Unit]          = ???
    override def cacheIfNotPresent(key: UserId, value: AccountData): Unit = ???
    override def printCache(): Unit                                       = ???
  }

  private val LoginClient: LoginClient = new LoginClientImpl(trackingService)

  val AuthenticationManager: AuthenticationManager = {
    val loginResult = Await.result(LoginClient.login(testEmailCredentials), 1.minute) match {
      case Right(result) => result
      case Left(errResponse) => throw errResponse
    }

    val userInfo = Await.result(LoginClient.getSelfUserInfo(loginResult.accessToken), 1.minute) match {
      case Right(result) => result
      case Left(errResponse) => throw errResponse
    }

    val testAccountData = AccountData(
      id = userInfo.id,
      teamId = userInfo.teamId,
      cookie = loginResult.cookie.get,
      accessToken = Some(loginResult.accessToken)
    )

    Await.ready(InMemoryAccountStorage.insert(testAccountData), 1.minute)

    new AuthenticationManager(userInfo.id, InMemoryAccountStorage, LoginClient, trackingService)
  }

  implicit val authRequestInterceptor: AuthRequestInterceptor = new AuthRequestInterceptor(AuthenticationManager, HttpClient)

}
