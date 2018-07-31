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
import com.waz.content.AccountStorage2
import com.waz.model.AccountData.Password
import com.waz.model.{AccountData, EmailAddress, UserId}
import com.waz.sync.client.{AuthenticationManager, LoginClient, LoginClientImpl}
import com.waz.utils.UnlimitedInMemoryStorage
import com.waz.znet2.http.HttpClient
import com.waz.znet2.http.Request.UrlCreator
import com.waz.znet2.{AuthRequestInterceptor, HttpClientOkHttpImpl}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object Config {

  val testEmailCredentials = EmailCredentials(EmailAddress("mykhailo+5@wire.com"), Password("123456789"))

  val BackendUrl: String = "https://staging-nginz-https.zinfra.io"

  implicit val HttpClient: HttpClient = HttpClientOkHttpImpl(enableLogging = true)
  implicit val urlCreator: UrlCreator = UrlCreator.simpleAppender(BackendUrl)

  private val trackingService = new DisabledTrackingService

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

    val accountStorage = new UnlimitedInMemoryStorage[UserId, AccountData](_.id) with AccountStorage2
    Await.ready(accountStorage.save(testAccountData), 1.minute)

    new AuthenticationManager(userInfo.id, accountStorage, LoginClient, trackingService)
  }

  implicit val authRequestInterceptor: AuthRequestInterceptor = new AuthRequestInterceptor(AuthenticationManager, HttpClient)

}
