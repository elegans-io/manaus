package com.getjenny.manaus.commands

import com.getjenny.manaus.util._
import com.getjenny.manaus._
import breeze.io.{CSVReader, CSVWriter}
import java.io.{File, FileWriter, FileReader}
import scala.io.Source
import org.elasticsearch.action.search._
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.index.query.{MatchAllQueryBuilder, QueryBuilders}
import org.elasticsearch.script._
import org.elasticsearch.script.Script
import scala.collection.JavaConverters._
import java.util.Collections


object CommandsUtils {

 def readPriorOccurrencesMap(word_frequencies: String,
                              wordColumn: Int = 1, occurrenceColumn: Int = 2): TokensOccurrences = {
    val priorOccurrencesMap: Map[String, Int] = Source.fromFile(word_frequencies).getLines
      .map(line => {
        val splitted_line = line.split("\t")
        splitted_line(wordColumn).toLowerCase -> line.split("\t")(occurrenceColumn).toInt
      }).toMap.withDefaultValue(0)

    val priorOccurrences = new PriorTokensOccurrencesMap(priorOccurrencesMap)
    priorOccurrences
  }

  def getSentences(conversations_file: String): Stream[IndexedSeq[String]] = {
    val file = new File(conversations_file)
    val file_reader = new FileReader(file)
    val file_entries = CSVReader.read(input=file_reader, separator=';',
      quote='"', escape='\\', skipLines=0)
    file_entries.toStream
  }

  def buildObservedOccurrencesMapFromConversationsFormat1(conversations_file: String) = {


    // list of tokenized sentences grouped by conversation
    // (sentence, tokenized_sentence, type, conv_id, sentence_id)
    def sentences = getSentences(conversations_file).map(line => {
      val tokenized_sentence = line(0).split(" ").toList.filter(_ != "").map(w => w.toLowerCase)
      (line(0), tokenized_sentence, line(1), line(2), line(3))
    })

    println("INFO: calculating observedOcurrencesMap")
    val observedOccurrencesMap = sentences.flatMap(line => line._2)
      .foldLeft(Map.empty[String, Int]){
        (count, word) => count + (word -> (count.getOrElse(word, 0) + 1))
      }

    val observedOccurrences = new ObservedTokensOccurrencesMap(observedOccurrencesMap)

    (sentences, observedOccurrences)
  }

  def buildObservedOccurrencesMapFromConversationsFormat2(conversations_file: String) = {


    // list of tokenized sentences grouped by conversation
    // (sentence, tokenized_sentence, type, conv_id, sentence_id)
    def sentences = getSentences(conversations_file).map(line => {
      val tokenized_sentence = line(0).split(" ").toList.filter(_ != "").map(w => w.toLowerCase)
      (line(1), tokenized_sentence)
    })

    println("INFO: calculating observedOcurrencesMap")
    val observedOccurrencesMap = sentences.flatMap(line => line._2)
      .foldLeft(Map.empty[String, Int]){
        (count, word) => count + (word -> (count.getOrElse(word, 0) + 1))
      }

    val observedOccurrences = new ObservedTokensOccurrencesMap(observedOccurrencesMap)

    (sentences, observedOccurrences)
  }

  def search(elastic_client : ElasticClient):
      Stream[(String, String)] = {

    elastic_client.open_client()
    val client: TransportClient = elastic_client.get_client()
    val qb: MatchAllQueryBuilder = QueryBuilders.matchAllQuery()

    var scrollResp: SearchResponse = client.prepareSearch(elastic_client.index_name)
      .setScroll("2m")
      .setTypes(elastic_client.type_name)
      .setQuery(qb)
      .setSize(100).get() //max of 100 hits will be returned for each scroll

    val documents: Stream[(String, String)] = Stream.continually({
      val hits = scrollResp.getHits.getHits
      val scrollId = scrollResp.getScrollId
      scrollResp = client.prepareSearchScroll(scrollId)
        .setScroll(new TimeValue(60000)).execute().actionGet()
      hits
    }).takeWhile(_.length != 0).flatten.map(hit => {
      val id = hit.getId
      val source : Map[String, Any] = hit.getSource.asScala.toMap
      val question : String = source.get("question") match {
        case Some(t) => t.asInstanceOf[String]
        case None => ""
      }
      (question, id)
    })

   documents
  }

  def searchAndGetTokens(field_name: String, elastic_client: ElasticClient): Stream[(List[String], String)] = {

    elastic_client.open_client()
    val client: TransportClient = elastic_client.get_client()
    val qb: MatchAllQueryBuilder = QueryBuilders.matchAllQuery()

    val script_text = "doc[\"" + field_name + "\"].values"

    val script: Script = new Script(
      ScriptType.INLINE,
      "painless",
      script_text,
      Collections.emptyMap())

    var scrollResp: SearchResponse = client.prepareSearch(elastic_client.index_name)
      .setScroll("2m")
      .setTypes(elastic_client.type_name)
      .setQuery(qb)
      .addScriptField("analyzed_tokens", script)
      .setSize(100).get() //max of 100 hits will be returned for each scroll

    val documents: Stream[(List[String], String)] = Stream.continually({
      val hits = scrollResp.getHits.getHits
      val scrollId = scrollResp.getScrollId
      scrollResp = client.prepareSearchScroll(scrollId)
        .setScroll(new TimeValue(60000)).execute().actionGet()
      hits
    }).takeWhile(_.length != 0).flatten.map(hit => {
      val id = hit.getId
      val analyzed_tokens = hit.fields.get("analyzed_tokens").asScala.map(x => {
        val token = x.asInstanceOf[String]
        token
      })

      (analyzed_tokens.toList, id)
    })

   documents
  }

}
