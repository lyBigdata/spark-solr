package com.lucidworks.spark

import java.util.UUID
import java.util.regex.Pattern

import com.lucidworks.spark.query.sql.SolrSQLSupport
import com.lucidworks.spark.rdd.SolrRDD
import com.lucidworks.spark.util.ConfigurationConstants._
import com.lucidworks.spark.util.QueryConstants._
import com.lucidworks.spark.util._
import com.typesafe.scalalogging.LazyLogging
import org.apache.http.entity.StringEntity
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrQuery.SortClause
import org.apache.solr.client.solrj.io.stream.expr._
import org.apache.solr.client.solrj.request.schema.SchemaRequest.{AddField, MultiUpdate, Update}
import org.apache.solr.common.SolrException.ErrorCode
import org.apache.solr.common.params.{CommonParams, ModifiableSolrParams}
import org.apache.solr.common.{SolrException, SolrInputDocument}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SQLContext, SparkSession}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.reflect.runtime.universe._
import scala.util.control.Breaks._

class SolrRelation(
    val parameters: Map[String, String],
    val dataFrame: Option[DataFrame],
    @transient val sparkSession: SparkSession)(
  implicit
    val conf: SolrConf = new SolrConf(parameters))
  extends BaseRelation
  with Serializable
  with TableScan
  with PrunedFilteredScan
  with InsertableRelation
  with LazyLogging {

  override val sqlContext: SQLContext = sparkSession.sqlContext

  def this(parameters: Map[String, String], sparkSession: SparkSession) {
    this(parameters, None, sparkSession)
  }

  checkRequiredParams()

  var collection = conf.getCollection.getOrElse({
    var coll = Option.empty[String]
    if (conf.getSqlStmt.isDefined) {
      val collectionFromSql = SolrRelation.findSolrCollectionNameInSql(conf.getSqlStmt.get)
      if (collectionFromSql.isDefined) {
        coll = collectionFromSql
      }
    }
    if (coll.isDefined) {
      logger.info(s"resolved collection option from sql to be ${coll.get}")
      coll.get
    } else {
      throw new IllegalArgumentException("collection option is required!")
    }
  })

  // Warn about unknown parameters
  val unknownParams = SolrRelation.checkUnknownParams(parameters.keySet)
  if (unknownParams.nonEmpty)
    logger.warn("Unknown parameters passed to query: " + unknownParams.toString())

  if (conf.partition_by.isDefined && conf.partition_by.get=="time") {
    val feature = new PartitionByTimeQueryParams(conf)
    val p = new PartitionByTimeQuerySupport(feature,conf)
    val allCollections = p.getPartitionsForQuery()
    collection = allCollections mkString ","
  }

  val arbitraryParams = conf.getArbitrarySolrParams
  val solrFields: Array[String] = {
    if (arbitraryParams.getParameterNames.contains(CommonParams.FL)) {
      arbitraryParams.getParams(CommonParams.FL).flatMap(f => f.split(",")).map(field => field.trim)
    } else {
      conf.getFields
    }
  }

  // we don't need the baseSchema for streaming expressions, so we wrap it in an optional
  var baseSchema : Option[StructType] = None

  val uniqueKey: String = SolrQuerySupport.getUniqueKey(conf.getZkHost.get, collection.split(",")(0))
  val initialQuery: SolrQuery = buildQuery
  // Preserve the initial filters if any present in arbitrary config
  var queryFilters: Array[String] = if (initialQuery.getFilterQueries != null) initialQuery.getFilterQueries else Array.empty[String]

  val querySchema: StructType = {
    if (dataFrame.isDefined) {
      dataFrame.get.schema
    } else {
      if (initialQuery.getFields != null) {
        baseSchema = Some(getBaseSchemaFromConfig(collection, solrFields))
        SolrRelationUtil.deriveQuerySchema(initialQuery.getFields.split(","), baseSchema.get)
      } else if (initialQuery.getRequestHandler == QT_STREAM) {
        var fieldSet: scala.collection.mutable.Set[StructField] = scala.collection.mutable.Set[StructField]()
        var streamingExpr = StreamExpressionParser.parse(initialQuery.get(SOLR_STREAMING_EXPR))
        if (conf.getStreamingExpressionSchema.isDefined) {
          // the user is telling us the schema of the expression
          var streamingExprSchema = conf.getStreamingExpressionSchema.get
          streamingExprSchema.split(',').foreach(f => {
            val pair : Array[String] = f.split(':')
            fieldSet.add(new StructField(pair.apply(0), DataType.fromJson("\""+pair.apply(1)+"\"")))
          })
        } else {
          // we have to figure out the schema of the streaming expression
          var streamOutputFields = new ListBuffer[StreamFields]
          findStreamingExpressionFields(streamingExpr, streamOutputFields, 0)
          logger.info(s"Found ${streamOutputFields.size} stream output fields: ${streamOutputFields}")
          for (sf <- streamOutputFields) {
            val streamSchema: StructType =
              SolrRelationUtil.getBaseSchema(
                sf.fields.map(f => f.name).toSet,
                conf.getZkHost.get,
                sf.collection,
                conf.escapeFieldNames.getOrElse(false),
                conf.flattenMultivalued.getOrElse(true),
                conf.skipNonDocValueFields.getOrElse(false))
            logger.debug(s"Got stream schema: ${streamSchema} for ${sf}")
            sf.fields.foreach(fld => {
              val fieldName = fld.alias.getOrElse(fld.name)
              if (fld.hasReplace) {
                // completely ignore the Solr type ... force to the replace type
                fieldSet.add(new StructField(fieldName, fld.dataType))
              } else {
                if (streamSchema.fieldNames.contains(fieldName)) {
                  fieldSet.add(streamSchema.apply(fieldName))
                } else {
                  // ugh ... this field coming out of the streaming expression isn't known to solr, so likely a select
                  // expression here with some renaming going on ... just assume string and keep going
                  fieldSet.add(new StructField(fieldName, fld.dataType))
                }
              }
            })

            sf.metrics.foreach(m => {
              val metricName = m.alias.getOrElse(m.name)
              // crazy hack here but Solr might return a long which we registered as a double in the schema
              if (!m.name.startsWith("count(")) {
                var promoteFields = initialQuery.get("promote_to_double")
                if (promoteFields == null) {
                  initialQuery.set("promote_to_double", metricName)
                } else {
                  initialQuery.set("promote_to_double", promoteFields+","+metricName)
                }
                logger.info(s"Set promote_to_double="+initialQuery.get("promote_to_double"))
                fieldSet.add(new StructField(metricName, DoubleType))
              } else {
                fieldSet.add(new StructField(metricName, m.dataType))
              }
            })
          }
        }

        if (fieldSet.isEmpty) {
          throw new IllegalStateException("Failed to extract schema fields for streaming expression: " + streamingExpr)
        }
        var exprSchema = new StructType(fieldSet.toArray.sortBy(f => f.name))
        logger.info(s"Created combined schema with ${exprSchema.fieldNames.size} fields for streaming expression: ${exprSchema}: ${exprSchema.fields}")
        exprSchema
      } else if (initialQuery.getRequestHandler == QT_SQL) {
        val sqlStmt = initialQuery.get(SOLR_SQL_STMT)
        val fieldSet: scala.collection.mutable.Set[StructField] = scala.collection.mutable.Set[StructField]()
        logger.info(s"Determining schema for Solr SQL: ${sqlStmt}")
        if (conf.getSolrSQLSchema.isDefined) {
          val solrSQLSchema = conf.getSolrSQLSchema.get
          logger.info(s"Using '$solrSQLSchema' from config property '$SOLR_SQL_SCHEMA' to compute the SQL schema")
          solrSQLSchema.split(',').foreach(f => {
            val pair : Array[String] = f.split(':')
            fieldSet.add(new StructField(pair.apply(0), DataType.fromJson("\""+pair.apply(1)+"\"")))
          })
        } else {
          val allFieldsSchema : StructType = getBaseSchemaFromConfig(collection, Array.empty)
          baseSchema = Some(allFieldsSchema)
          val sqlColumns = SolrSQLSupport.parseColumns(sqlStmt).asScala
          logger.info(s"Parsed SQL fields: ${sqlColumns}")
          if (sqlColumns.isEmpty)
            throw new IllegalArgumentException(s"Cannot determine schema for DataFrame backed by Solr SQL query: ${sqlStmt}; be sure to specify desired columns explicitly instead of relying on the 'SELECT *' syntax.")

          sqlColumns.foreach((kvp) => {
            var lower = kvp._1.toLowerCase
            var col = kvp._2
            if (lower.startsWith("count(")) {
              fieldSet.add(new StructField(kvp._2, LongType))
            } else if (lower.startsWith("avg(") || lower.startsWith("min(") || lower.startsWith("max(") || lower.startsWith("sum(")) {
              fieldSet.add(new StructField(kvp._2, DoubleType))

              // todo: this is hacky but needed to work around SOLR-9372 where the type returned from Solr differs
              // based on the aggregation mode used to execute the SQL statement
              var promoteFields = initialQuery.get("promote_to_double")
              if (promoteFields == null) {
                promoteFields = kvp._2
                initialQuery.set("promote_to_double", promoteFields)
              } else {
                initialQuery.set("promote_to_double", promoteFields+","+kvp._2)
              }
            }  else if (col.equals("score")) {
              fieldSet.add(new StructField(col, DoubleType))
            } else {
              if (allFieldsSchema.fieldNames.contains(kvp._2)) {
                val existing = allFieldsSchema.fields(allFieldsSchema.fieldIndex(kvp._2))
                fieldSet.add(existing)
                logger.debug(s"Found existing field ${kvp._2}: ${existing}")
              } else {
                fieldSet.add(new StructField(kvp._2, StringType))
              }
            }
          })
        }
        if (fieldSet.isEmpty) {
          throw new IllegalStateException("Failed to extract schema fields for streaming expression: " + sqlStmt)
        }
        val sqlSchema = new StructType(fieldSet.toArray.sortBy(f => f.name))
        logger.info(s"Created schema ${sqlSchema} for SQL: ${sqlStmt}")
        sqlSchema
      } else {
        // don't return the _version_ field unless specifically asked for by the user
        baseSchema = Some(getBaseSchemaFromConfig(collection, solrFields))
        if (solrFields.contains("_version_")) {
          // user specifically requested _version_, so keep it
          var tmp = applyExcludeFieldsToSchema(baseSchema.get)
          StructType(tmp.fields.sortBy(f => f.name))
        } else {
          var tmp = baseSchema.get
          if (tmp.fieldNames.contains("_version_")) {
            tmp = StructType(tmp.filter(p => p.name != "_version_"))
          }
          tmp = applyExcludeFieldsToSchema(tmp)
          StructType(tmp.fields.sortBy(f => f.name))
        }
      }
    }
  }

  private def applyExcludeFieldsToSchema(querySchema: StructType) : StructType = {
    if (!conf.getExcludeFields.isDefined)
      return querySchema

    val excludeFields = conf.getExcludeFields.get.trim()
    if (excludeFields.isEmpty)
      return querySchema

    logger.info(s"Found field name exclusion patterns: ${excludeFields}")
    val excludePatterns = excludeFields.split(",").map(pat => {
      var namePattern = pat.trim()
      val len = namePattern.length()
      // since leading or trailing wildcards are so common, we'll convert those to proper regex for the user
      // otherwise, newbies will get the ol' "Dangling meta character '*' near index 0" error
      if (namePattern.startsWith("*") && namePattern.indexOf("*", 1) == -1) {
        namePattern = "^.*"+namePattern.substring(1)+"$"
      } else if (namePattern.endsWith("*") && namePattern.substring(0,len-1).indexOf("*") == -1) {
        namePattern = "^"+namePattern.substring(0,len-1)+".*$"
      }
      Pattern.compile(namePattern, Pattern.CASE_INSENSITIVE)
    })

    return StructType(querySchema.filter(p => {
      var isExcluded = false
      for (regex <- excludePatterns) {
        if (regex.matcher(p.name).matches()) {
          isExcluded = true
          logger.debug(s"Excluding ${p.name} from the query schema because it matches exclude pattern: ${regex.pattern()}")
        }
      }
      !isExcluded
    }))
  }

  def getBaseSchemaFromConfig(collection: String, solrFields: Array[String]) : StructType = {
    SolrRelationUtil.getBaseSchema(
      solrFields.toSet,
      conf.getZkHost.get,
      collection.split(",")(0),
      conf.escapeFieldNames.getOrElse(false),
      conf.flattenMultivalued.getOrElse(true),
      conf.skipNonDocValueFields.getOrElse(false))
  }

  def findStreamingExpressionFields(expr: StreamExpressionParameter, streamOutputFields: ListBuffer[StreamFields], depth: Int) : Unit = {

    logger.info(s"findStreamingExpressionFields(depth=$depth): expr = $expr")

    var currDepth = depth

    expr match {
      case subExpr : StreamExpression =>
        val funcName = subExpr.getFunctionName
        if (funcName == "search" || funcName == "random" || funcName == "facet") {
          extractSearchFields(subExpr).foreach(fld => streamOutputFields += fld)
        } else if (funcName == "select") {
          // the top-level select is the schema we care about so we don't need to scan expressions below the
          // top-level select for fields in the schema, hence the special casing here
          if (depth == 0) {

            currDepth += 1

            logger.info(s"Extracting search fields from top-level select")
            var exprCollection : Option[String] = Option.empty[String]
            var fields = scala.collection.mutable.ListBuffer.empty[StreamField]
            var metrics = scala.collection.mutable.ListBuffer.empty[StreamField]
            var replaceFields = scala.collection.mutable.Map[String, DataType]()
            subExpr.getParameters.asScala.foreach(sub => {
              sub match {
                case v : StreamExpressionValue => {
                  val selectFieldName = v.getValue
                  val asAt = selectFieldName.indexOf(" as ")
                  if (asAt != -1) {
                    val key = selectFieldName.substring(0, asAt).trim()
                    val alias = selectFieldName.substring(asAt + 4).trim()
                    if (key.indexOf("(") != -1 && key.endsWith(")")) {
                      metrics += getStreamMetricField(key, Some(alias))
                    } else {
                      fields += new StreamField(key, StringType, Some(alias))
                    }
                  } else {
                    if (selectFieldName.indexOf("(") != -1 && selectFieldName.endsWith(")")) {
                      metrics += getStreamMetricField(selectFieldName, None)
                    } else {
                      fields += new StreamField(selectFieldName, StringType, None)
                    }
                  }
                }
                case e : StreamExpression => {
                  val exprFuncName = e.getFunctionName
                  if (exprFuncName == "replace") {
                    // we have to handle type-conversion from the Solr type to the replace type
                    logger.debug(s"Found a replace expression in select: $e")
                    val params = e.getParameters.asScala
                    val tmp = params.apply(0)
                    tmp match {
                      case v: StreamExpressionValue => replaceFields += (v.getValue -> StringType)
                    }
                  } else if (exprFuncName == "search" || exprFuncName == "random" || exprFuncName == "facet") {
                    val maybeSF = extractSearchFields(e)
                    if (maybeSF.isDefined) {
                      exprCollection = Some(maybeSF.get.collection)
                      logger.debug(s"Found exprCollection name ")
                    }
                  }
                }
                case _ => // ugly, but let's not fail b/c of unhandled stuff as the expression stuff is changing rapidly
              }
            })

            // for any fields that have a replace function applied, we need to override the
            fields = fields.map(f => {
              if (replaceFields.contains(f.name)) new StreamField(f.name, replaceFields.getOrElse(f.name, StringType), None, true) else f
            })

            val streamFields = new StreamFields(exprCollection.getOrElse(collection), fields, metrics)
            logger.info(s"Extracted $streamFields for $subExpr")
            streamOutputFields += streamFields
          } else {
            // not a top-level select, so just push it down to find fields
            extractSearchFields(subExpr).foreach(fld => streamOutputFields += fld)
          }
        } else {
          subExpr.getParameters.asScala.foreach(subParam => findStreamingExpressionFields(subParam, streamOutputFields, currDepth))
        }
      case namedParam : StreamExpressionNamedParameter => findStreamingExpressionFields(namedParam.getParameter, streamOutputFields, currDepth)
      case _ => // no op
    }
  }

  private def getStreamMetricField(key: String, alias: Option[String]) : StreamField = {
    return if (key.startsWith("count(")) {
      new StreamField(key, LongType, alias)
    } else {
      // just treat all other metrics as double type
      new StreamField(key, DoubleType, alias)
    }
  }

  def extractSearchFields(subExpr: StreamExpression) : Option[StreamFields] = {
    logger.debug(s"Extracting search fields from ${subExpr.getFunctionName} stream expression ${subExpr} of type ${subExpr.getClass.getName}")
    var collection : Option[String] = Option.empty[String]
    var fields = scala.collection.mutable.ListBuffer.empty[StreamField]
    var metrics = scala.collection.mutable.ListBuffer.empty[StreamField]
    subExpr.getParameters.asScala.foreach(sub => {
      logger.debug(s"Next expression param is $sub of type ${sub.getClass.getName}")
      sub match {
        case p : StreamExpressionNamedParameter =>
          if (p.getName == "fl" || p.getName == "buckets" && subExpr.getFunctionName == "facet") {
            p.getParameter match {
              case value : StreamExpressionValue => value.getValue.split(",").foreach(v => fields += new StreamField(v, StringType, None))
              case _ => // ugly!
            }
          }
        case v : StreamExpressionValue => collection = Some(v.getValue)
        case e : StreamExpression => if (subExpr.getFunctionName == "facet") {
            // a metric for a facet stream
            metrics += getStreamMetricField(e.toString, None)
          }
        case _ => // ugly!
      }
    })
    if (collection.isDefined && !fields.isEmpty) {
      val streamFields = new StreamFields(collection.get, fields, metrics)
      logger.info(s"Extracted $streamFields for $subExpr")
      return Some(streamFields)
    }
    None
  }

  override def schema: StructType = querySchema

  override def buildScan(): RDD[Row] = buildScan(Array.empty, Array.empty)

  override def buildScan(fields: Array[String], filters: Array[Filter]): RDD[Row] = {
    
    logger.info("buildScan: push-down fields: [" + fields.mkString(",") + "], filters: ["+filters.mkString(",")+"]")

    val query = initialQuery.getCopy
    var qt = query.getRequestHandler

    val solrRDD = {
      var rdd = new SolrRDD(
        conf.getZkHost.get,
        collection,
        sqlContext.sparkContext,
        Some(qt),
        uKey = Some(uniqueKey))

      if (conf.getSplitsPerShard.isDefined) {
        // always apply this whether we're doing splits or not so that the user
        // can pass splits_per_shard=1 to disable splitting when there are multiple replicas
        // as we now will do splitting using the HashQParser when there are multiple active replicas
        rdd = rdd.splitsPerShard(conf.getSplitsPerShard.get)
      }

      if (conf.splits.isDefined) {
        rdd = rdd.doSplits()

        if (!conf.getSplitsPerShard.isDefined) {
          // user wants splits, but didn't specify how many per shard
          rdd = rdd.splitsPerShard(DEFAULT_SPLITS_PER_SHARD)
        }
      }

      if (conf.getSplitField.isDefined) {
        rdd = rdd.splitField(conf.getSplitField.get)

        if (!conf.getSplitsPerShard.isDefined) {
          // user wants splits, but didn't specify how many per shard
          rdd = rdd.splitsPerShard(DEFAULT_SPLITS_PER_SHARD)
        }
      }

      rdd
    }

    if (qt == QT_STREAM || qt == QT_SQL) {
      // ignore any fields / filters when processing a streaming expression
      return SolrRelationUtil.toRows(querySchema, solrRDD.requestHandler(qt).query(query))
    }

    // this check is probably unnecessary, but I'm putting it here so that it's clear to other devs
    // that the baseSchema must be defined if we get to this point
    if (baseSchema.isEmpty) {
      throw new IllegalStateException("No base schema defined for collection "+collection)
    }

    val collectionBaseSchema = baseSchema.get
    if (fields != null && fields.length > 0) {
      fields.zipWithIndex.foreach({ case (field, i) => fields(i) = field.replaceAll("`", "") })
      query.setFields(fields: _*)
    }

    // Clear all existing filters except the original filters set in the config.
    if (!filters.isEmpty) {
      query.setFilterQueries(queryFilters:_*)
      filters.foreach(filter => SolrRelationUtil.applyFilter(filter, query, collectionBaseSchema))
    } else {
      query.setFilterQueries(queryFilters:_*)
    }

    if (conf.sampleSeed.isDefined) {
      // can't support random sampling & intra-shard splitting
      if (conf.splits.getOrElse(false) || conf.getSplitField.isDefined) {
        throw new IllegalStateException("Cannot do sampling if intra-shard splitting feature is enabled!");
      }

      query.addSort(SolrQuery.SortClause.asc("random_"+conf.sampleSeed.get))
      query.addSort(SolrQuery.SortClause.asc(solrRDD.uniqueKey))
      query.add(ConfigurationConstants.SAMPLE_PCT, conf.samplePct.getOrElse(0.1f).toString)
    }

    try {
      val querySchema = if (!fields.isEmpty) SolrRelationUtil.deriveQuerySchema(fields, collectionBaseSchema) else schema

      if (querySchema.fields.length == 0) {
        throw new IllegalStateException(s"No fields defined in query schema for query: ${query}. This is likely an issue with the Solr collection ${collection}, does it have data?")
      }

      if (conf.requestHandler.isEmpty && !requiresExportHandler(qt) && !conf.useCursorMarks.getOrElse(false) && !conf.splits.getOrElse(false)) {
        logger.info(s"Checking the query and sort fields to determine if streaming is possible for ${collection}")
        // Determine whether to use Streaming API (/export handler) if 'use_export_handler' or 'use_cursor_marks' options are not set
        val hasUnsupportedExportTypes : Boolean = SolrRelation.checkQueryFieldsForUnsupportedExportTypes(querySchema)
        val isFDV: Boolean = if (fields.isEmpty && query.getFields == null) true else SolrRelation.checkQueryFieldsForDV(querySchema)
        var sortClauses: ListBuffer[SortClause] = ListBuffer.empty
        if (!query.getSorts.isEmpty) {
          for (sort: SortClause <- query.getSorts.asScala) {
            sortClauses += sort
          }
        } else {
          val sortParams = query.getParams(CommonParams.SORT)
          if (sortParams != null && sortParams.nonEmpty) {
            for (sortString <- sortParams) {
              sortClauses = sortClauses ++ SolrRelation.parseSortParamFromString(sortString)
            }
          }
        }

        logger.debug(s"Existing sort clauses: ${sortClauses.mkString}")

        val isSDV: Boolean =
          if (sortClauses.nonEmpty)
            SolrRelation.checkSortFieldsForDV(collectionBaseSchema, sortClauses.toList)
          else
            if (isFDV && !hasUnsupportedExportTypes) {
              SolrRelation.addSortField(querySchema, query)
              logger.info("Added sort field '" + query.getSortField + "' to the query")
              true
            }
            else
              false

        if (isFDV && isSDV && !hasUnsupportedExportTypes) {
          qt = QT_EXPORT
          query.setRequestHandler(qt)
          logger.info("Using the /export handler because docValues are enabled for all fields and no unsupported field types have been requested.")
        } else {
          logger.debug(s"Using requestHandler: $qt isFDV? $isFDV and isSDV? $isSDV and hasUnsupportedExportTypes? $hasUnsupportedExportTypes")
        }
        // For DataFrame operations like count(), no fields are passed down but the export handler only works when fields are present
        if (qt == QT_EXPORT) {
          if (query.getFields == null)
            query.setFields(solrRDD.uniqueKey)
          if (query.getSorts.isEmpty && (query.getParams(CommonParams.SORT) == null || query.getParams(CommonParams.SORT).isEmpty))
            query.setSort(solrRDD.uniqueKey, SolrQuery.ORDER.asc)
        }
      }
      logger.info(s"Sending ${query} to SolrRDD using ${qt}")
      val docs = solrRDD.requestHandler(qt).query(query)
      val rows = SolrRelationUtil.toRows(querySchema, docs)
      rows
    } catch {
      case e: Throwable => throw new RuntimeException(e)
    }
  }

  def requiresExportHandler(rq: String): Boolean = {
    return rq == QT_EXPORT || rq == QT_STREAM || rq == QT_SQL
  }

  def toSolrType(dataType: DataType): String = {
    dataType match {
      case bi: BinaryType => "binary"
      case b: BooleanType => "boolean"
      case dt: DateType => "tdate"
      case db: DoubleType => "tdouble"
      case dec: DecimalType => "tdouble"
      case ft: FloatType => "tfloat"
      case i: IntegerType => "tint"
      case l: LongType => "tlong"
      case s: ShortType => "tint"
      case t: TimestampType => "tdate"
      case _ => "string"
    }
  }

  def toAddFieldMap(sf: StructField): Map[String,AnyRef] = {
    val map = scala.collection.mutable.Map[String,AnyRef]()
    map += ("name" -> sf.name)
    map += ("indexed" -> "true")
    map += ("stored" -> "true")
    map += ("docValues" -> "true")
    val dataType = sf.dataType
    dataType match {
      case at: ArrayType =>
        map += ("multiValued" -> "true")
        map += ("type" -> toSolrType(at.elementType))
      case _ =>
        map += ("multiValued" -> "false")
        map += ("type" -> toSolrType(dataType))
    }
    map.toMap
  }

  override def insert(df: DataFrame, overwrite: Boolean): Unit = {

    val zkHost = conf.getZkHost.get
    val collectionId = conf.getCollection.get
    val dfSchema = df.schema
    val solrBaseUrl = SolrSupport.getSolrBaseUrl(zkHost)
    val solrFields : Map[String, SolrFieldMeta] =
      SolrQuerySupport.getFieldTypes(Set(), solrBaseUrl, collectionId)

    // build up a list of updates to send to the Solr Schema API
    val fieldsToAddToSolr = new ListBuffer[Update]()
    dfSchema.fields.foreach(f => {
      // TODO: we should load all dynamic field extensions from Solr for making a decision here
      if (!solrFields.contains(f.name) && !SolrRelationUtil.isValidDynamicFieldName(f.name)) {
        logger.info(s"adding new field: "+toAddFieldMap(f).asJava)
        fieldsToAddToSolr += new AddField(toAddFieldMap(f).asJava)
      }
    })

    val cloudClient = SolrSupport.getCachedCloudClient(zkHost)
    val solrParams = new ModifiableSolrParams()
    solrParams.add("updateTimeoutSecs","30")
    val addFieldsUpdateRequest = new MultiUpdate(fieldsToAddToSolr.asJava, solrParams)

    if (fieldsToAddToSolr.nonEmpty) {
      logger.info(s"Sending request to Solr schema API to add ${fieldsToAddToSolr.size} fields.")
      val updateResponse : org.apache.solr.client.solrj.response.schema.SchemaResponse.UpdateResponse =
        addFieldsUpdateRequest.process(cloudClient, collectionId)
      if (updateResponse.getStatus >= 400) {
        val errMsg = "Schema update request failed due to: "+updateResponse
        logger.error(errMsg)
        throw new SolrException(ErrorCode.getErrorCode(updateResponse.getStatus), errMsg)
      }
    }

    if (conf.softAutoCommitSecs.isDefined) {
      logger.info("softAutoCommitSecs? "+conf.softAutoCommitSecs)
      val softAutoCommitSecs = conf.softAutoCommitSecs.get
      val softAutoCommitMs = softAutoCommitSecs * 1000
      var configApi = solrBaseUrl
      if (!configApi.endsWith("/")) {
        configApi += "/"
      }
      configApi += collectionId+"/config"

      val postRequest = new org.apache.http.client.methods.HttpPost(configApi)
      val configJson = "{\"set-property\":{\"updateHandler.autoSoftCommit.maxTime\":\""+softAutoCommitMs+"\"}}";
      postRequest.setEntity(new StringEntity(configJson))
      logger.info("POSTing: "+configJson+" to "+configApi)
      SolrJsonSupport.doJsonRequest(cloudClient.getLbClient.getHttpClient, configApi, postRequest)
    }

    val batchSize: Int = if (conf.batchSize.isDefined) conf.batchSize.get else 1000
    val generateUniqKey: Boolean = conf.genUniqKey.getOrElse(false)

    // Convert RDD of rows in to SolrInputDocuments
    val docs = df.rdd.map(row => {
      val schema: StructType = row.schema
      val doc = new SolrInputDocument
      schema.fields.foreach(field => {
        val fname = field.name
        breakable {
          if (fname.equals("_version_")) break()
          val fieldIndex = row.fieldIndex(fname)
          val fieldValue : Option[Any] = if (row.isNullAt(fieldIndex)) None else Some(row.get(fieldIndex))
          if (fieldValue.isDefined) {
            val value = fieldValue.get
            value match {
              //TODO: Do we need to check explicitly for ArrayBuffer and WrappedArray
              case v: Iterable[Any] =>
                val it = v.iterator
                while (it.hasNext) doc.addField(fname, it.next())
              case bd: java.math.BigDecimal =>
                doc.setField(fname, bd.doubleValue())
              case _ => doc.setField(fname, value)
            }
          }
        }
      })

      // Generate unique key if the document doesn't have one
      if (generateUniqKey) {
        if (!doc.containsKey(uniqueKey)) {
          doc.setField(uniqueKey, UUID.randomUUID().toString)
        }
      }
      doc
    })
    SolrSupport.indexDocs(zkHost, collectionId, batchSize, docs, conf.commitWithin)
  }

  private def buildQuery: SolrQuery = {
    val query = SolrQuerySupport.toQuery(conf.getQuery.getOrElse("*:*"))

    if (conf.getStreamingExpr.isDefined) {
      query.setRequestHandler(QT_STREAM)
      query.set(SOLR_STREAMING_EXPR, conf.getStreamingExpr.get.replaceAll("\\s+", " "))
    } else if (conf.getSqlStmt.isDefined) {
      query.setRequestHandler(QT_SQL)
      query.set(SOLR_SQL_STMT, conf.getSqlStmt.get.replaceAll("\\s+", " "))
    } else if (conf.requestHandler.isDefined) {
      query.setRequestHandler(conf.requestHandler.get)
    } else {
      query.setRequestHandler(DEFAULT_REQUEST_HANDLER)
    }

    if (solrFields.nonEmpty) {
      query.setFields(solrFields:_*)
    }

    query.setRows(scala.Int.box(conf.getRows.getOrElse(DEFAULT_PAGE_SIZE)))
    if (conf.getSort.isDefined) {
      val sortClauses = SolrRelation.parseSortParamFromString(conf.getSort.get)
      for (sortClause <- sortClauses) {
        query.addSort(sortClause)
      }
    }
    
    val sortParams = conf.getArbitrarySolrParams.remove("sort")
    if (sortParams != null && sortParams.length > 0) {
      for (p <- sortParams) {
        val sortClauses = SolrRelation.parseSortParamFromString(p)
        for (sortClause <- sortClauses) {
          query.addSort(sortClause)
        }
      }
    }
    query.add(conf.getArbitrarySolrParams)
    query.set("collection", collection)
    query
  }

  private def checkRequiredParams(): Unit = {
    require(conf.getZkHost.isDefined, "Param '" + SOLR_ZK_HOST_PARAM + "' is required")
  }
}

object SolrRelation extends LazyLogging {

  val solrCollectionInSqlPattern = Pattern.compile("\\sfrom\\s([\\w\\-\\.]+)\\s?", Pattern.CASE_INSENSITIVE)

  def findSolrCollectionNameInSql(sqlText: String): Option[String] = {
    val collectionIdMatcher = solrCollectionInSqlPattern.matcher(sqlText)
    if (!collectionIdMatcher.find()) {
      logger.warn(s"No push-down to Solr! Cannot determine collection name from Solr SQL query: ${sqlText}")
      return None
    }
    Some(collectionIdMatcher.group(1))
  }

  def checkUnknownParams(keySet: Set[String]): Set[String] = {
    var knownParams = Set.empty[String]
    var unknownParams = Set.empty[String]

    // Use reflection to get all the members of [ConfigurationConstants] except 'CONFIG_PREFIX'
    val rm = scala.reflect.runtime.currentMirror
    val accessors = rm.classSymbol(ConfigurationConstants.getClass).toType.members.collect {
      case m: MethodSymbol if m.isGetter && m.isPublic => m
    }
    val instanceMirror = rm.reflect(ConfigurationConstants)

    for(acc <- accessors) {
      knownParams += instanceMirror.reflectMethod(acc).apply().toString
    }

    // Check for any unknown options
    keySet.foreach(key => {
      if (!knownParams.contains(key)) {
        unknownParams += key
      }
    })
    unknownParams
  }

  def checkQueryFieldsForDV(querySchema: StructType) : Boolean = {
    // Check if all the fields in the querySchema have docValues enabled
    for (structField <- querySchema.fields) {
      val metadata = structField.metadata
      if (!metadata.contains("docValues"))
        return false
      if (metadata.contains("docValues") && !metadata.getBoolean("docValues"))
        return false
    }
    true
  }

  def checkSortFieldsForDV(baseSchema: StructType, sortClauses: List[SortClause]): Boolean = {

    if (sortClauses.nonEmpty) {
      // Check if the sorted field (if exists) has docValue enabled
      for (sortClause: SortClause <- sortClauses) {
        val sortField = sortClause.getItem
        if (baseSchema.fieldNames.contains(sortField)) {
          val sortFieldMetadata = baseSchema(sortField).metadata
          if (!sortFieldMetadata.contains("docValues"))
            return false
          if (sortFieldMetadata.contains("docValues") && !sortFieldMetadata.getBoolean("docValues"))
            return false
        } else {
          logger.warn("The sort field '" + sortField + "' does not exist in the base schema")
          return false
        }
      }
      true
    } else {
      false
   }
  }

  def addSortField(querySchema: StructType, query: SolrQuery): Unit = {
    // if doc values enabled for the id field, then sort by that
    if (querySchema.fieldNames.contains("id")) {
      query.addSort("id", SolrQuery.ORDER.asc)
      return
    }
    querySchema.fields.foreach(field => {
      if (field.metadata.contains("multiValued")) {
        if (!field.metadata.getBoolean("multiValued")) {
          query.addSort(field.name, SolrQuery.ORDER.asc)
          return
        }
      }
      query.addSort(field.name, SolrQuery.ORDER.asc)
      return
    })
  }

  // TODO: remove this check when https://issues.apache.org/jira/browse/SOLR-9187 is fixed
  def checkQueryFieldsForUnsupportedExportTypes(querySchema: StructType) : Boolean = {
    for (structField <- querySchema.fields) {
      if (structField.dataType == BooleanType)
        return true
    }
    false
  }

  def parseSortParamFromString(sortParam: String):  List[SortClause] = {
    val sortClauses: ListBuffer[SortClause] = ListBuffer.empty
    for (pair <- sortParam.split(",")) {
      val sortStringParams = pair.split(" ")
      if (sortStringParams.nonEmpty) {
        if (sortStringParams.size == 2) {
          sortClauses += new SortClause(sortStringParams(0), sortStringParams(1))
        } else {
          sortClauses += SortClause.asc(pair)
        }
      }
    }
    sortClauses.toList
  }
}

case class StreamField(name:String, dataType: DataType, alias:Option[String], hasReplace: Boolean = false)
case class StreamFields(collection:String,fields:ListBuffer[StreamField],metrics:ListBuffer[StreamField])

