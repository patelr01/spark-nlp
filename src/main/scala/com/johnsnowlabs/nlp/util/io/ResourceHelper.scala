package com.johnsnowlabs.nlp.util.io

import java.io.{File, FileNotFoundException, InputStream}

import com.johnsnowlabs.nlp.annotators.{Normalizer, RegexTokenizer}
import com.johnsnowlabs.nlp.{DocumentAssembler, Finisher}
import com.johnsnowlabs.nlp.util.io.ResourceFormat._
import org.apache.spark.ml.Pipeline
import org.apache.spark.sql.SparkSession

import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import scala.io.Source

import java.net.URLDecoder
import java.util.jar.JarFile


/**
  * Created by saif on 28/04/17.
  */

/**
  * Helper one-place for IO management. Streams, source and external input should be handled from here
  */
object ResourceHelper {

  private val spark: SparkSession = SparkSession.builder().getOrCreate()

  /** Structure for a SourceStream coming from compiled content */
  case class SourceStream(resource: String) {
    val pipe: Option[InputStream] = {
      var stream = getClass.getResourceAsStream(resource)
      if (stream == null)
        stream = getClass.getClassLoader.getResourceAsStream(resource)
      Option(stream)
    }
    val content: Source = pipe.map(p => {
      Source.fromInputStream(p)("UTF-8")
    }).getOrElse(Source.fromFile(resource, "UTF-8"))
    def close(): Unit = {
      content.close()
      pipe.foreach(_.close())
    }
  }

  def listDirectory(path: String): Seq[String] = {
    var dirURL = getClass.getResource(path)

    if (dirURL == null)
      dirURL = getClass.getClassLoader.getResource(path)

    if (dirURL != null && dirURL.getProtocol.equals("file")) {
      /* A file path: easy enough */
      return new File(dirURL.toURI).list().sorted
    } else if (dirURL == null) {
      /* path not in resources and not in disk */
      throw new FileNotFoundException(path)
    }

    if (dirURL.getProtocol.equals("jar")) {
      /* A JAR path */
      val jarPath = dirURL.getPath.substring(5, dirURL.getPath.indexOf("!")) //strip out only the JAR file
      val jar = new JarFile(URLDecoder.decode(jarPath, "UTF-8"))
      val entries = jar.entries()
      val result = new ArrayBuffer[String]()

      val pathToCheck = path.replaceFirst("/", "")
      while(entries.hasMoreElements) {
        val name = entries.nextElement().getName.replaceFirst("/", "")
        if (name.startsWith(pathToCheck)) { //filter according to the path
          var entry = name.substring(pathToCheck.length())
          val checkSubdir = entry.indexOf("/")
          if (checkSubdir >= 0) {
            // if it is a subdirectory, we just return the directory name
            entry = entry.substring(0, checkSubdir)
          }
          if (entry.nonEmpty)
            result.append(entry)
        }
      }
      return result.distinct.sorted
    }

    throw new UnsupportedOperationException(s"Cannot list files for URL $dirURL")
  }

  /** Checks whether a path points to directory */
  def pathIsDirectory(path: String): Boolean = {
    //ToDo: Improve me???
    if (path.contains(".txt")) false else true
  }

  /**
    * General purpose key value parser from source
    * Currently read only text files
    * @param source File input to streamline
    * @param format format
    * @param keySep separator of keys, values taken as single
    * @return
    */
  def parseKeyValueText(
                          source: String,
                          format: Format,
                          keySep: String
                        ): Map[String, String] = {
    format match {
      case TXT =>
        val sourceStream = SourceStream(source)
        val res = sourceStream.content.getLines.map (line => {
          val kv = line.split (keySep).map (_.trim)
          (kv.head, kv.last)
        }).toMap
        sourceStream.close()
        res
      case TXTDS =>
        import spark.implicits._
        val dataset = spark.read.option("delimiter", keySep).csv(source).toDF("key", "value")
        val keyValueStore = MMap.empty[String, String]
        dataset.as[(String, String)].foreach{kv => keyValueStore(kv._1) = kv._2}
        keyValueStore.toMap
      case _ =>
        throw new Exception("Unsupported format. Must be TXT or TXTDS")
    }
  }

  /**
    * General purpose line parser from source
    * Currently read only text files
    * @param source File input to streamline
    * @param format format
    * @return
    */
  def parseLinesText(
                      source: String,
                      format: Format
                     ): Array[String] = {
    format match {
      case TXT =>
        val sourceStream = SourceStream(source)
        val res = sourceStream.content.getLines.toArray
        sourceStream.close()
        res
      case TXTDS =>
        import spark.implicits._
        val dataset = spark.read.text(source)
        val lineStore = spark.sparkContext.collectionAccumulator[String]
        dataset.as[String].foreach(l => lineStore.add(l))
        val result = lineStore.value.toArray.map(_.toString)
        lineStore.reset()
        result
      case _ =>
        throw new Exception("Unsupported format. Must be TXT or TXTDS")
    }
  }

  /**
    * General purpose tuple parser from source
    * Currently read only text files
    * @param source File input to streamline
    * @param format format
    * @param keySep separator of tuples
    * @return
    */
  def parseTupleText(
                         source: String,
                         format: Format,
                         keySep: String
                       ): Array[(String, String)] = {
    format match {
      case TXT =>
        val sourceStream = SourceStream(source)
        val res = sourceStream.content.getLines.map (line => {
          val kv = line.split (keySep).map (_.trim)
          (kv.head, kv.last)
        }).toArray
        sourceStream.close()
        res
      case TXTDS =>
        import spark.implicits._
        val dataset = spark.read.text(source)
        val lineStore = spark.sparkContext.collectionAccumulator[String]
        dataset.as[String].foreach(l => lineStore.add(l))
        val result = lineStore.value.toArray.map(line => {
          val kv = line.toString.split (keySep).map (_.trim)
          (kv.head, kv.last)
        })
        lineStore.reset()
        result
      case _ =>
        throw new Exception("Unsupported format. Must be TXT or TXTDS")
    }
  }

  /**
    * For multiple values per keys, this optimizer flattens all values for keys to have constant access
    * @param source File input to streamline
    * @param format format, for now only txt
    * @param keySep separator cha
    * @param valueSep values separator in dictionary
    * @return
    */
  def flattenRevertValuesAsKeys(
                                 source: String,
                                 format: Format,
                                 keySep: String,
                                 valueSep: String): Map[String, String] = {
    format match {
      case TXT =>
        val m: MMap[String, String] = MMap()
        val sourceStream = SourceStream(source)
        sourceStream.content.getLines.foreach(line => {
          val kv = line.split(keySep).map(_.trim)
          val key = kv(0)
          val values = kv(1).split(valueSep).map(_.trim)
          values.foreach(m(_) = key)
        })
        sourceStream.close()
        m.toMap
      case TXTDS =>
        import spark.implicits._
        val dataset = spark.read.text(source)
        val valueAsKeys = MMap.empty[String, String]
        dataset.as[String].foreach(line => {
          val kv = line.split(keySep).map(_.trim)
          val key = kv(0)
          val values = kv(1).split(valueSep).map(_.trim)
          values.foreach(v => valueAsKeys(v) = key)
        })
        valueAsKeys.toMap
      case _ =>
        throw new Exception("Unsupported format. Must be TXT or TXTDS")
    }
  }

  def wordCount(
                 source: String,
                 format: Format,
                 tokenPattern: String,
                 m: MMap[String, Int] = MMap.empty[String, Int].withDefaultValue(0)
               ): MMap[String, Int] = {
    format match {
      case TXT =>
        val regex = tokenPattern.r
        if (pathIsDirectory(source)) {
          try {
            new File(source).listFiles()
              .flatMap(fileName => wordCount(fileName.toString, format, tokenPattern, m))
          } catch {
            case _: NullPointerException =>
              val sourceStream = SourceStream(source)
              sourceStream
                .content
                .getLines()
                .flatMap(fileName => wordCount(source + "/" + fileName, format, tokenPattern, m))
                .toArray
              sourceStream.close()
          }
        } else {
          val sourceStream = SourceStream(source)
          sourceStream.content.getLines.foreach(line => {
            val words = regex.findAllMatchIn(line).map(_.matched).toList
              words.foreach(w => {
                m(w) += 1
              })
          })
          sourceStream.close()
        }
        if (m.isEmpty) throw new FileNotFoundException("Word count dictionary for spell checker does not exist or is empty")
        m
      case TXTDS =>
        import spark.implicits._
        val dataset = spark.read.textFile(source)
        val wordCount = MMap.empty[String, Int].withDefaultValue(0)
        val documentAssembler = new DocumentAssembler()
          .setInputCol("value")
        val tokenizer = new RegexTokenizer()
          .setInputCols("document")
          .setOutputCol("token")
          .setPattern(tokenPattern)
        val normalizer = new Normalizer()
          .setInputCols("token")
          .setOutputCol("normal")
        val finisher = new Finisher()
          .setInputCols("normal")
          .setOutputCols("finished")
          .setAnnotationSplitSymbol("--")
        new Pipeline()
          .setStages(Array(documentAssembler, tokenizer, normalizer, finisher))
          .fit(dataset)
          .transform(dataset)
          .select("finished").as[String]
          .foreach(text => text.split("--").foreach(t => {
            wordCount(t) += 1
          }))
        wordCount
      case _ => throw new IllegalArgumentException("format not available for word count")
    }
  }

  def ViveknWordCount(
                       source: String,
                       tokenPattern: String,
                       prune: Int,
                       f: (List[String] => List[String]),
                       left: MMap[String, Int] = MMap.empty[String, Int].withDefaultValue(0),
                       right: MMap[String, Int] = MMap.empty[String, Int].withDefaultValue(0)
               ): (MMap[String, Int], MMap[String, Int]) = {
    val regex = tokenPattern.r
    val prefix = "not_"
    if (pathIsDirectory(source)) {
      try {
        new File(source).listFiles()
          .map(fileName => ViveknWordCount(fileName.toString, tokenPattern, prune, f, left, right))
      } catch {
        case _: NullPointerException =>
          val sourceStream = SourceStream(source)
          sourceStream
            .content
            .getLines()
            .map(fileName => ViveknWordCount(source + "/" + fileName, tokenPattern, prune, f, left, right))
            .toArray
          sourceStream.close()
      }
    } else {
      val sourceStream = SourceStream(source)
      sourceStream.content.getLines.foreach(line => {
        val words = regex.findAllMatchIn(line).map(_.matched).toList
        f.apply(words).foreach(w => {
          left(w) += 1
          right(prefix + w) += 1
        })
      })
      sourceStream.close()
    }
    if (left.isEmpty || right.isEmpty) throw new FileNotFoundException("Word count dictionary for spell checker does not exist or is empty")
    if (prune > 0)
      (left.filter{case (_, v) => v > 1}, right.filter{case (_, v) => v > 1})
    else
      (left, right)
  }

}
