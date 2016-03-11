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

package engine

import akka.actor.ActorRef
import controllers.AuditActor.SetRuleApplied
import controllers.Auditing
import model.{Location, _}
import play.api.Logger
import play.api.mvc.{AnyContent, Request}
import uk.gov.hmrc.play.frontend.auth.AuthContext
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

import scala.concurrent.Future

trait RuleEngine extends Auditing {

  def auditActor: Future[ActorRef]

  val rules: List[Rule]

  def getLocation(authContext: AuthContext, ruleContext: RuleContext, auditContext: TAuditContext)(implicit request: Request[AnyContent], hc: HeaderCarrier): Future[Option[Location]] = {

    rules.foldLeft(Future[Option[Location]](None)) {
      (location, rule) => location.flatMap(candidateLocation => if (candidateLocation.isDefined) location
      else {
        val ruleApplyResult: Future[Option[Location]] = rule.apply(authContext, ruleContext, auditContext)
        val ruleName = rule.name
        ruleApplyResult.flatMap { case result =>
          if (result.isDefined) {
            withAuditing(auditActor, SetRuleApplied(authContext.user.userId, ruleName)) {
              Logger.debug(s"rule applied: $ruleName")
              result
            }
          }
          else Future.successful {
            Logger.debug(s"rule evaluated but not applied: $ruleName")
            result
          }
        }
      })
    }
  }
}
