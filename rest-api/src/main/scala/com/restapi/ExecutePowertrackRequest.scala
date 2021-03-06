/**
 * (C) Copyright IBM Corp. 2015, 2016
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
 *
 */

package com.restapi

import java.text.SimpleDateFormat
import java.util.{Calendar, TimeZone}
import org.apache.commons.lang.time.DateUtils
import org.slf4j.LoggerFactory
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, future, Await}
import play.api.libs.json._


/**
 * Created by barbaragomes on 10/16/15.
 */
object ExecutePowertrackRequest {

  val logger = LoggerFactory.getLogger(this.getClass)

  def runPowertrackAnalysis(batchTime: Int, topTweets: Int, topWords: Int,
                            termsInclude: String, termsExclude: String,
                            user: String): Future[String] = {
    val (startDate, endDate) = getStartAndEndDateAccordingBatchTime(batchTime)
    logger.info(s"UTC start date: $startDate")
    logger.info(s"UTC end date: $endDate")
    /* Temporary fix to search for #SparkSummitEU when searching for #SparkSummit */
    val tempIncludeTerms = if (termsInclude.toLowerCase().trim() == "#sparksummit") {
      s"$termsInclude,#sparksummiteu"
    }
    else {
      termsInclude
    }
    logger.info(s"User: $user")
    logger.info(s"Included terms: $tempIncludeTerms")
    logger.info(s"Excluded terms: $termsExclude")

    executeAsynchronous(batchTime, topTweets, topWords, tempIncludeTerms,
                        termsExclude, startDate, endDate) map { js =>
      Json.stringify(js)
    }
  }

  def executeAsynchronous(batchTime: Int, topTweets: Int, topWords: Int,
                          termsInclude: String, termsExclude: String,
                          startDate: String, endDate: String): Future[JsValue] = {
    val elasticsearchResponse = new GetElasticsearchResponse(topTweets,
      termsInclude.toLowerCase().trim().split(","), termsExclude.toLowerCase().trim().split(","),
      startDate, endDate, LoadConf.esConf.getString("powertrackIndexName"))

    val wordCountJson: Future[JsObject] = future {
      getTweetsAndWordCount(elasticsearchResponse, topWords)
    }
    val totalUserAndTweetsJson: Future[JsObject] = future {
      getUsersAndTweets(elasticsearchResponse)
    }
    val totalRetweetsJson: Future[JsObject] = future {
      getRetweetsCount(elasticsearchResponse)
    }

    val tasks: Seq[Future[JsObject]] = Seq(wordCountJson, totalUserAndTweetsJson, totalRetweetsJson)
    val aggregated: Future[Seq[JsObject]] = Future.sequence(tasks)

    val result = aggregated.map(jsonResults => jsonResults(0) ++ jsonResults(1) ++ jsonResults(2))

    result.recover {
      case e: Exception =>
        logger.error("Execute Powertrack Word Count", e)
        Json.obj("toptweets" -> Json.obj("tweets" -> JsNull), "wordCount" -> JsNull,
          "totalfilteredtweets" -> JsNull, "totalusers" -> JsNull,
          "totalretweets" -> JsNull, "success" -> true)
    }
  }

  def getRetweetsCount(elasticsearchResponse: GetElasticsearchResponse): JsObject = {
    try {
      val countResponse = Json.parse(elasticsearchResponse.getTotalRetweets())
      return Json.obj("totalretweets" -> (countResponse \ "hits" \ "total"))
    }
    catch {
      case e: Exception =>
        logger.error("Powertrack user and tweets count", e)
        Json.obj("totalretweets" -> JsNull)
    }
  }

  def getUsersAndTweets(elasticsearchResponse: GetElasticsearchResponse): JsObject = {
    try {
      /* The Elasticsearch query used to calculate unique values executes a Cardinality Aggregation.
       * This query requires the pre-compute of the field hash on indexing time.
       * This is a really expensive query and you can experience slow performance on a big dataset.
       * If you are using a big dataset, please make sure that your ES cluster reflects the size of the data.
       */
      val countResponse =
      Json.parse(elasticsearchResponse.getTotalFilteredTweetsAndTotalUserResponse())
      return (Json.obj("totalfilteredtweets" -> (countResponse \ "hits" \ "total")) ++
      Json.obj("totalusers" ->
      (countResponse \ "aggregations" \ "distinct_users_by_id" \ "value")))
    }
    catch {
      case e: Exception =>
        logger.error("Powertrack user and tweets count", e)
        Json.obj("totalfilteredtweets" -> JsNull, "totalusers" -> JsNull)
    }
  }

  def getTweetsAndWordCount(elasticsearchResponse: GetElasticsearchResponse,
                            topWords: Int): JsObject = {
    try {
      val response = Json.parse(elasticsearchResponse.getPowertrackTweetsAndWordCount(topWords))
      var tweets = ((response \ "hits" \ "hits").as[List[JsObject]])

      if (LoadConf.restConf.getBoolean("validateTweetsBeforeDisplaying")) {
        val tweetsID = Json.obj("messages" -> tweets.map(tweet => (tweet \ "_source" \ "tweet_id")))
        val nonCompliantTweets = ValidateTweetCompliance.getNonCompliantTweets(Json.stringify(tweetsID)) // scalastyle:ignore
        if (!nonCompliantTweets.isEmpty) {
          tweets = tweets.filter(tweet => !nonCompliantTweets.contains((tweet \ "_source" \ "tweet_id").as[String])) // scalastyle:ignore
        }
      }

      val validatedTweets = tweets.map(tweet => {
        Json.obj(
          "created_at" -> (tweet \ "_source" \ "created_at"),
          "text" -> (tweet \ "_source" \ "tweet_text"),
          "user" -> Json.obj(
            "name" -> (tweet \ "_source" \ "user_name"),
            "screen_name" -> (tweet \ "_source" \ "user_handle"),
            "followers_count" -> (tweet \ "_source" \ "user_followers_count"),
            "id" -> (tweet \ "_source" \ "user_id"),
            "profile_image_url" -> (tweet \ "_source" \ "user_image_url")
          ))
      }
      )

      val words = ((response \ "aggregations" \ "top_words" \ "buckets").as[List[JsObject]]).map(wordCount => { // scalastyle:ignore
        Json.arr((wordCount \ "key"), (wordCount \ "doc_count"))
      })

      Json.obj("toptweets" -> Json.obj("tweets" -> validatedTweets), "wordCount" -> words)
    }
    catch {
      case e: Exception =>
        logger.error("Powertrack word count", e)
        Json.obj("toptweets" -> Json.obj("tweets" -> JsNull), "wordCount" -> JsNull)
    }
  }

  def getStartAndEndDateAccordingBatchTime(batchTime: Int): (String, String) = {
    // end date should be the current date
    val endDate = Calendar.getInstance().getTime()
    val startDate = DateUtils.addMinutes(endDate, -batchTime)

    // Powertrack datetime timezine: UTC
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    val sdf: SimpleDateFormat = new SimpleDateFormat(LoadConf.globalConf.getString("spark.powertrack.tweetTimestampFormat")) // scalastyle:ignore
    (sdf.format(startDate), sdf.format(endDate))
  }
}
