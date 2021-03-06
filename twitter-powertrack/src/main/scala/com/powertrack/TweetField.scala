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
package com.powertrack

/* Mapping tweet fields */
object TweetField {
  val jsonPrefix = "tweets"
  // unique id of the tweet as string format
  val tweet_id = "message.id"
  // tweet text as string format
  val tweet_text = "message.body"
  // tweet created date as string format
  val tweet_created_at = "message.postedTime"
  // user description as string format
  val user_description = "message.actor.summary"
  // user profile image URL as string format
  val user_profileImgURL = "message.actor.image"
  // user followers count as long format
  val user_followers_count = "message.actor.followersCount"
  // user name as string format
  val user_name = "message.actor.displayName"
  // user handle as string format
  val user_handle = "message.actor.preferredUsername"
  // user id as long format
  val user_id = "message.actor.id"
  // user language as string format
  val language = "message.actor.languages"
  // array, get first ?
  // defines the type of the tweet obj
  val verb = "message.verb"
}
