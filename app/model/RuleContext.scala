/*
 * Copyright 2016 HM Revenue & Customs
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

package model

import connector._
import uk.gov.hmrc.play.frontend.auth.AuthContext
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

import scala.concurrent.Future

case class RuleContext(authContext: AuthContext)(implicit hc: HeaderCarrier) {
  val governmentGatewayConnector: GovernmentGatewayConnector = GovernmentGatewayConnector
  val selfAssessmentConnector: SelfAssessmentConnector = SelfAssessmentConnector
  val frontendAuthConnector: FrontendAuthConnector = FrontendAuthConnector

  lazy val activeEnrolments: Future[Set[String]] = {
    val futureProfile = governmentGatewayConnector.profile
    futureProfile.map { profile =>
      profile.enrolments.filter(_.state == EnrolmentState.ACTIVATED).map(_.key).toSet[String]
    }
  }

  lazy val lastSaReturn = authContext.principal.accounts.sa
    .fold(Future(SaReturn.empty))(saAccount => selfAssessmentConnector.lastReturn(saAccount.utr.value))

  lazy val currentCoAFEAuthority = frontendAuthConnector.currentCoAFEAuthority()
}
