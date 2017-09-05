/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark

import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._
import scala.collection.mutable.LinkedHashSet

import org.apache.avro.{Schema, SchemaNormalization}

import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.{ConfigEntry, OptionalConfigEntry}
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.util.Utils

/**
 * Configuration for a Spark application. Used to set various Spark parameters as key-value pairs.
 *
 * Most of the time, you would create a SparkConf object with `new SparkConf()`, which will load
 * values from any `spark.*` Java system properties set in your application as well. In this case,
 * parameters you set directly on the `SparkConf` object take priority over system properties.
 *
 * For unit tests, you can also call `new SparkConf(false)` to skip loading external settings and
 * get the same configuration no matter what the system properties are.
 *
 * All setter methods in this class support chaining. For example, you can write
 * `new SparkConf().setMaster("local").setAppName("My app")`.
 *
 * Note that once a SparkConf object is passed to Spark, it is cloned and can no longer be modified
 * by the user. Spark does not support modifying the configuration at runtime.
  * 传给SparkContext的SparkConf是拷贝的,Spark不支持运行时修改配置
 *
 * @param loadDefaults whether to also load values from Java system properties
 */
class SparkConf(loadDefaults: Boolean) extends Cloneable with Logging {

  import SparkConf._

  /** Create a SparkConf that loads defaults from system properties and the classpath */
  def this() = this(true)

  private val settings = new ConcurrentHashMap[String, String]()// SparkConf维护了一个线程安全的HashMap,用于存储所有的配置信息

  //1   位于构造函数中，在创建对象的时候就会执行
  if (loadDefaults) { // 如果设置了从classpath加载配置参数(以saprk开头的,默认为true)
    loadFromSystemProperties(false) // false为是否不Warn已经被废弃的配置
  }

  //2
  private[spark] def loadFromSystemProperties(silent: Boolean): SparkConf = {
    // Load any spark.* system properties
    for ((key, value) <- Utils.getSystemProperties if key.startsWith("spark.")) { // 使用了带守卫的for循环
      set(key, value, silent)
    }
    this
  }

  /** Set a configuration variable. */
  def set(key: String, value: String): SparkConf = {
    set(key, value, false)// 调用重载的方法
  }

  //3
  private[spark] def set(key: String, value: String, silent: Boolean): SparkConf = {
    if (key == null) {
      throw new NullPointerException("null key")
    }
    if (value == null) {
      throw new NullPointerException("null value for " + key)
    }
    if (!silent) {
      logDeprecationWarning(key)// Warn被废弃的配置参数
    }
    settings.put(key, value)//将参数放入HashMap
    this//返回SparkConf对象
  }

  private[spark] def set[T](entry: ConfigEntry[T], value: T): SparkConf = {
    set(entry.key, entry.stringConverter(value))
    this
  }

  private[spark] def set[T](entry: OptionalConfigEntry[T], value: T): SparkConf = {
    set(entry.key, entry.rawStringConverter(value))
    this
  }

  /**
   * The master URL to connect to, such as "local" to run locally with one thread, "local[4]" to
   * run locally with 4 cores, or "spark://master:7077" to run on a Spark standalone cluster.
   */
  def setMaster(master: String): SparkConf = {
    set("spark.master", master)//设置master
  }

  /** Set a name for your application. Shown in the Spark web UI. */
  def setAppName(name: String): SparkConf = {
    set("spark.app.name", name)//设置spark App的名字,在web UI上回显示
  }

  /** Set JAR files to distribute to the cluster. */
  def setJars(jars: Seq[String]): SparkConf = {
    for (jar <- jars if (jar == null)) logWarning("null jar passed to SparkContext constructor")
    set("spark.jars", jars.filter(_ != null).mkString(","))
  }

  /** Set JAR files to distribute to the cluster. (Java-friendly version.) */
  def setJars(jars: Array[String]): SparkConf = {
    setJars(jars.toSeq)
  }

  /**
   * Set an environment variable to be used when launching executors for this application.
   * These variables are stored as properties of the form spark.executorEnv.VAR_NAME
   * (for example spark.executorEnv.PATH) but this method makes them easier to set.
   */
  def setExecutorEnv(variable: String, value: String): SparkConf = {
    set("spark.executorEnv." + variable, value)//设置executor的环境变量
  }

  /**
   * Set multiple environment variables to be used when launching executors.
   * These variables are stored as properties of the form spark.executorEnv.VAR_NAME
   * (for example spark.executorEnv.PATH) but this method makes them easier to set.
   */
  def setExecutorEnv(variables: Seq[(String, String)]): SparkConf = {
    for ((k, v) <- variables) {
      setExecutorEnv(k, v)
    }
    this
  }

  /**
   * Set multiple environment variables to be used when launching executors.
   * (Java-friendly version.)
   */
  def setExecutorEnv(variables: Array[(String, String)]): SparkConf = {
    setExecutorEnv(variables.toSeq)
  }

  /**
   * Set the location where Spark is installed on worker nodes.
   */
  def setSparkHome(home: String): SparkConf = {
    set("spark.home", home)
  }

  /** Set multiple parameters together */
  def setAll(settings: Traversable[(String, String)]): SparkConf = {// 参数为可遍历接口的子类
    settings.foreach { case (k, v) => set(k, v) }//使用了模式匹配
    this
  }

  /** Set a parameter if it isn't already configured */
  def setIfMissing(key: String, value: String): SparkConf = {
    if (settings.putIfAbsent(key, value) == null) {
      logDeprecationWarning(key)
    }
    this
  }

  private[spark] def setIfMissing[T](entry: ConfigEntry[T], value: T): SparkConf = {
    if (settings.putIfAbsent(entry.key, entry.stringConverter(value)) == null) {
      logDeprecationWarning(entry.key)
    }
    this
  }

  private[spark] def setIfMissing[T](entry: OptionalConfigEntry[T], value: T): SparkConf = {
    if (settings.putIfAbsent(entry.key, entry.rawStringConverter(value)) == null) {
      logDeprecationWarning(entry.key)
    }
    this
  }

  /**
   * Use Kryo serialization and register the given set of classes with Kryo.
   * If called multiple times, this will append the classes from all calls together.
   */
  def registerKryoClasses(classes: Array[Class[_]]): SparkConf = {//用于序列化
    val allClassNames = new LinkedHashSet[String]()
    allClassNames ++= get("spark.kryo.classesToRegister", "").split(',').map(_.trim)
      .filter(!_.isEmpty)//取出来已经配置的
    allClassNames ++= classes.map(_.getName)//追加上程序里面又配置的

    set("spark.kryo.classesToRegister", allClassNames.mkString(","))
    set("spark.serializer", classOf[KryoSerializer].getName)
    this
  }

  private final val avroNamespace = "avro.schema."//avro相关参数的命名空间,用于压缩

  /**
   * Use Kryo serialization and register the given set of Avro schemas so that the generic
   * record serializer can decrease network IO
    * 将Avro的Schema类也进行注册,是为了在使用Avro压缩之后,再使用Kryo进行系列化,以减少网络IO
   */
  def registerAvroSchemas(schemas: Schema*): SparkConf = {
    for (schema <- schemas) {
      set(avroNamespace + SchemaNormalization.parsingFingerprint64(schema), schema.toString)
    }
    this
  }

  /** Gets all the avro schemas in the configuration used in the generic Avro record serializer */
  def getAvroSchema: Map[Long, String] = {
    getAll.filter { case (k, v) => k.startsWith(avroNamespace) }
      .map { case (k, v) => (k.substring(avroNamespace.length).toLong, v) }
      .toMap
  }

  /** Remove a parameter from the configuration */
  def remove(key: String): SparkConf = {
    settings.remove(key)
    this
  }

  private[spark] def remove(entry: ConfigEntry[_]): SparkConf = {
    remove(entry.key)
  }

  /** Get a parameter; throws a NoSuchElementException if it's not set */
  def get(key: String): String = {
    getOption(key).getOrElse(throw new NoSuchElementException(key))
  }

  /** Get a parameter, falling back to a default if not set */
  def get(key: String, defaultValue: String): String = {
    getOption(key).getOrElse(defaultValue)
  }

  /**
   * Retrieves the value of a pre-defined configuration entry.
   *
   * - This is an internal Spark API.
   * - The return type if defined by the configuration entry.
   * - This will throw an exception is the config is not optional and the value is not set.
   */
  private[spark] def get[T](entry: ConfigEntry[T]): T = {
    entry.readFrom(this)
  }

  /**
   * Get a time parameter as seconds; throws a NoSuchElementException if it's not set. If no
   * suffix is provided then seconds are assumed.
   * @throws NoSuchElementException
   */
  def getTimeAsSeconds(key: String): Long = {
    Utils.timeStringAsSeconds(get(key))//将时间相关的参数转换成秒,一般配置中是毫秒
  }

  /**
   * Get a time parameter as seconds, falling back to a default if not set. If no
   * suffix is provided then seconds are assumed.
   */
  def getTimeAsSeconds(key: String, defaultValue: String): Long = {
    Utils.timeStringAsSeconds(get(key, defaultValue))
  }

  /**
   * Get a time parameter as milliseconds; throws a NoSuchElementException if it's not set. If no
   * suffix is provided then milliseconds are assumed.
   * @throws NoSuchElementException
   */
  def getTimeAsMs(key: String): Long = {
    Utils.timeStringAsMs(get(key))//将字符串的毫秒值转成long
  }

  /**
   * Get a time parameter as milliseconds, falling back to a default if not set. If no
   * suffix is provided then milliseconds are assumed.
   */
  def getTimeAsMs(key: String, defaultValue: String): Long = {
    Utils.timeStringAsMs(get(key, defaultValue))
  }

  /**
   * Get a size parameter as bytes; throws a NoSuchElementException if it's not set. If no
   * suffix is provided then bytes are assumed.
   * @throws NoSuchElementException
   */
  def getSizeAsBytes(key: String): Long = {
    Utils.byteStringAsBytes(get(key))
  }

  /**
   * Get a size parameter as bytes, falling back to a default if not set. If no
   * suffix is provided then bytes are assumed.
   */
  def getSizeAsBytes(key: String, defaultValue: String): Long = {
    Utils.byteStringAsBytes(get(key, defaultValue))
  }

  /**
   * Get a size parameter as bytes, falling back to a default if not set.
   */
  def getSizeAsBytes(key: String, defaultValue: Long): Long = {
    Utils.byteStringAsBytes(get(key, defaultValue + "B"))
  }

  /**
   * Get a size parameter as Kibibytes; throws a NoSuchElementException if it's not set. If no
   * suffix is provided then Kibibytes are assumed.
   * @throws NoSuchElementException
   */
  def getSizeAsKb(key: String): Long = {
    Utils.byteStringAsKb(get(key))
  }

  /**
   * Get a size parameter as Kibibytes, falling back to a default if not set. If no
   * suffix is provided then Kibibytes are assumed.
   */
  def getSizeAsKb(key: String, defaultValue: String): Long = {
    Utils.byteStringAsKb(get(key, defaultValue))
  }

  /**
   * Get a size parameter as Mebibytes; throws a NoSuchElementException if it's not set. If no
   * suffix is provided then Mebibytes are assumed.
   * @throws NoSuchElementException
   */
  def getSizeAsMb(key: String): Long = {
    Utils.byteStringAsMb(get(key))
  }

  /**
   * Get a size parameter as Mebibytes, falling back to a default if not set. If no
   * suffix is provided then Mebibytes are assumed.
   */
  def getSizeAsMb(key: String, defaultValue: String): Long = {
    Utils.byteStringAsMb(get(key, defaultValue))
  }

  /**
   * Get a size parameter as Gibibytes; throws a NoSuchElementException if it's not set. If no
   * suffix is provided then Gibibytes are assumed.
   * @throws NoSuchElementException
   */
  def getSizeAsGb(key: String): Long = {
    Utils.byteStringAsGb(get(key))
  }

  /**
   * Get a size parameter as Gibibytes, falling back to a default if not set. If no
   * suffix is provided then Gibibytes are assumed.
   */
  def getSizeAsGb(key: String, defaultValue: String): Long = {
    Utils.byteStringAsGb(get(key, defaultValue))
  }

  /** Get a parameter as an Option */
  def getOption(key: String): Option[String] = {
    Option(settings.get(key)).orElse(getDeprecatedConfig(key, this))
  }

  /** Get all parameters as a list of pairs */
  def getAll: Array[(String, String)] = {
    settings.entrySet().asScala.map(x => (x.getKey, x.getValue)).toArray
  }

  /** Get a parameter as an integer, falling back to a default if not set */
  def getInt(key: String, defaultValue: Int): Int = {
    getOption(key).map(_.toInt).getOrElse(defaultValue)
  }

  /** Get a parameter as a long, falling back to a default if not set */
  def getLong(key: String, defaultValue: Long): Long = {
    getOption(key).map(_.toLong).getOrElse(defaultValue)
  }

  /** Get a parameter as a double, falling back to a default if not set */
  def getDouble(key: String, defaultValue: Double): Double = {
    getOption(key).map(_.toDouble).getOrElse(defaultValue)
  }

  /** Get a parameter as a boolean, falling back to a default if not set */
  def getBoolean(key: String, defaultValue: Boolean): Boolean = {
    getOption(key).map(_.toBoolean).getOrElse(defaultValue)
  }

  /** Get all executor environment variables set on this SparkConf */
  def getExecutorEnv: Seq[(String, String)] = {
    val prefix = "spark.executorEnv."
    getAll.filter{case (k, v) => k.startsWith(prefix)}
          .map{case (k, v) => (k.substring(prefix.length), v)}//取出去掉命名空间之后的参数名和参数值
  }

  /**
   * Returns the Spark application id, valid in the Driver after TaskScheduler registration and
   * from the start in the Executor.
    * //当TaskScheduler注册之后,Executor启动时会生成app id存入SparkConf的HashMap中
   */
  def getAppId: String = get("spark.app.id")

  /** Does the configuration contain a given parameter? */
  def contains(key: String): Boolean = {
    settings.containsKey(key) ||
      configsWithAlternatives.get(key).toSeq.flatten.exists { alt => contains(alt.key) }
  }

  /** Copy this object */
  override def clone: SparkConf = {
    val cloned = new SparkConf(false)
    settings.entrySet().asScala.foreach { e =>
      cloned.set(e.getKey(), e.getValue(), true)
    }
    cloned
  }

  /**
   * By using this instead of System.getenv(), environment variables can be mocked
   * in unit tests.
   */
  private[spark] def getenv(name: String): String = System.getenv(name)// 为了方便单元测试,封装了一层方法

  /**
   * Checks for illegal or deprecated config settings. Throws an exception for the former. Not
   * idempotent - may mutate this conf object to convert deprecated settings to supported ones.
    *
    * 验证配置，排除非法参数和已经被废弃的参数。不是幂等的。该对象可能会被修改，将废弃的参数转换成支持的参数。
   */

  private[spark] def validateSettings() {
    if (contains("spark.local.dir")) {
      val msg = "In Spark 1.0 and later spark.local.dir will be overridden by the value set by " +
        "the cluster manager (via SPARK_LOCAL_DIRS in mesos/standalone and LOCAL_DIRS in YARN)."
      logWarning(msg)
    }

    val executorOptsKey = "spark.executor.extraJavaOptions"
    val executorClasspathKey = "spark.executor.extraClassPath"
    val driverOptsKey = "spark.driver.extraJavaOptions"
    val driverClassPathKey = "spark.driver.extraClassPath"
    val driverLibraryPathKey = "spark.driver.extraLibraryPath"
    val sparkExecutorInstances = "spark.executor.instances"

    // Used by Yarn in 1.1 and before
    sys.props.get("spark.driver.libraryPath").foreach { value =>
      val warning =
        s"""
          |spark.driver.libraryPath was detected (set to '$value').
          |This is deprecated in Spark 1.2+.
          |
          |Please instead use: $driverLibraryPathKey
        """.stripMargin
      logWarning(warning)
    }

    // Validate spark.executor.extraJavaOptions
    getOption(executorOptsKey).foreach { javaOpts =>
      if (javaOpts.contains("-Dspark")) {//不让用JavaOpts设置Spark的参数
        val msg = s"$executorOptsKey is not allowed to set Spark options (was '$javaOpts'). " +
          "Set them directly on a SparkConf or in a properties file when using ./bin/spark-submit."
        throw new Exception(msg)
      }
      if (javaOpts.contains("-Xmx")) {//不让使用JVM参数设置最大堆空间
        val msg = s"$executorOptsKey is not allowed to specify max heap memory settings " +
          s"(was '$javaOpts'). Use spark.executor.memory instead."
        throw new Exception(msg)
      }
    }

    // Validate memory fractions 验证内存划分参数
    val deprecatedMemoryKeys = Seq(//被废弃
      "spark.storage.memoryFraction",
      "spark.shuffle.memoryFraction",
      "spark.shuffle.safetyFraction",
      "spark.storage.unrollFraction",
      "spark.storage.safetyFraction")
    val memoryKeys = Seq(
      "spark.memory.fraction",
      "spark.memory.storageFraction") ++
      deprecatedMemoryKeys
    for (key <- memoryKeys) {
      val value = getDouble(key, 0.5)
      if (value > 1 || value < 0) {//非法的划分比例
        throw new IllegalArgumentException(s"$key should be between 0 and 1 (was '$value').")
      }
    }


    // Warn against deprecated memory fractions (unless legacy memory management mode is enabled)
    val legacyMemoryManagementKey = "spark.memory.useLegacyMode"
    val legacyMemoryManagement = getBoolean(legacyMemoryManagementKey, false)
    if (!legacyMemoryManagement) {//如果spark.memory.useLegacyMode设置为false
      val keyset = deprecatedMemoryKeys.toSet//被废弃的参数key
      val detected = settings.keys().asScala.filter(keyset.contains)
      if (detected.nonEmpty) {
        logWarning("Detected deprecated memory fraction settings: " +
          detected.mkString("[", ", ", "]") + ". As of Spark 1.6, execution and storage " +
          "memory management are unified. All memory fractions used in the old model are " +
          "now deprecated and no longer read. If you wish to use the old memory management, " +
          s"you may explicitly enable `$legacyMemoryManagementKey` (not recommended).")
      }
    }

    // Check for legacy configs 验证历史版本遗留配置
    sys.env.get("SPARK_JAVA_OPTS").foreach { value =>
      val warning =
        s"""
          |SPARK_JAVA_OPTS was detected (set to '$value').//这种设置是各种进程统一设置
          |This is deprecated in Spark 1.0+.
          |
          |Please instead use:
          | //只为当前APP设置
          | - ./spark-submit with conf/spark-defaults.conf to set defaults for an application
          | //只为Driver设置
          | - ./spark-submit with --driver-java-options to set -X options for a driver
          | 只为executor设置
          | - spark.executor.extraJavaOptions to set -X options for executors
          | 只为守护进程设置
          | - SPARK_DAEMON_JAVA_OPTS to set java options for standalone daemons (master or worker)
        """.stripMargin
      logWarning(warning)

      for (key <- Seq(executorOptsKey, driverOptsKey)) {
        if (getOption(key).isDefined) {//重复设置
          throw new SparkException(s"Found both $key and SPARK_JAVA_OPTS. Use only the former.")
        } else {
          logWarning(s"Setting '$key' to '$value' as a work-around.")
          set(key, value)
        }
      }
    }

    sys.env.get("SPARK_CLASSPATH").foreach { value =>
      val warning =
        s"""
          |SPARK_CLASSPATH was detected (set to '$value').
          |This is deprecated in Spark 1.0+.
          |
          |Please instead use:
          |driver和executor分别设置
          | - ./spark-submit with --driver-class-path to augment the driver classpath
          | - spark.executor.extraClassPath to augment the executor classpath
        """.stripMargin
      logWarning(warning)

      for (key <- Seq(executorClasspathKey, driverClassPathKey)) {
        if (getOption(key).isDefined) {
          throw new SparkException(s"Found both $key and SPARK_CLASSPATH. Use only the former.")
        } else {
          logWarning(s"Setting '$key' to '$value' as a work-around.")
          set(key, value)
        }
      }
    }

    if (!contains(sparkExecutorInstances)) {
      sys.env.get("SPARK_WORKER_INSTANCES").foreach { value =>
        val warning =
          s"""
             |SPARK_WORKER_INSTANCES was detected (set to '$value').
             |This is deprecated in Spark 1.0+.
             |
             |Please instead use:
             | - ./spark-submit with --num-executors to specify the number of executors
             | - Or set SPARK_EXECUTOR_INSTANCES
             | - spark.executor.instances to configure the number of instances in the spark config.
        """.stripMargin
        logWarning(warning)

        set("spark.executor.instances", value)
      }
    }
    //调教作业的时候，master不再区分yarn-clinet和yarn-cluster,而是使用spark.submit.deployMode来区分了
    if (contains("spark.master") && get("spark.master").startsWith("yarn-")) {
      val warning = s"spark.master ${get("spark.master")} is deprecated in Spark 2.0+, please " +
        "instead use \"yarn\" with specified deploy mode."

      get("spark.master") match {
        case "yarn-cluster" =>
          logWarning(warning)
          set("spark.master", "yarn")
          set("spark.submit.deployMode", "cluster")
        case "yarn-client" =>
          logWarning(warning)
          set("spark.master", "yarn")
          set("spark.submit.deployMode", "client")
        case _ => // Any other unexpected master will be checked when creating scheduler backend.
      }
    }

    if (contains("spark.submit.deployMode")) {//验证deployMode
      get("spark.submit.deployMode") match {
        case "cluster" | "client" =>
        case e => throw new SparkException("spark.submit.deployMode can only be \"cluster\" or " +
          "\"client\".")
      }
    }
  }

  /**
   * Return a string listing all keys and values, one per line. This is useful to print the
   * configuration out for debugging.
   */
  def toDebugString: String = {//打印所有配置参数
    getAll.sorted.map{case (k, v) => k + "=" + v}.mkString("\n")
  }

}

private[spark] object SparkConf extends Logging {

  /**
   * Maps deprecated config keys to information about the deprecation.
   *
   * The extra information is logged as a warning when the config is present in the user's
   * configuration.
   */
    //废弃的不再用的参数
    //第5步
  private val deprecatedConfigs: Map[String, DeprecatedConfig] = {
    val configs = Seq(
      // DeprecatedConfig 是case class封装了参数名 开始废弃的版本号 说明信息
      DeprecatedConfig("spark.cache.class", "0.8",
        "The spark.cache.class property is no longer being used! Specify storage levels using " +
        "the RDD.persist() method instead."),
      DeprecatedConfig("spark.yarn.user.classpath.first", "1.3",
        "Please use spark.{driver,executor}.userClassPathFirst instead."),
      DeprecatedConfig("spark.kryoserializer.buffer.mb", "1.4",
        "Please use spark.kryoserializer.buffer instead. The default value for " +
          "spark.kryoserializer.buffer.mb was previously specified as '0.064'. Fractional values " +
          "are no longer accepted. To specify the equivalent now, one may use '64k'."),
      DeprecatedConfig("spark.rpc", "2.0", "Not used any more.")
    )

    Map(configs.map {
      cfg => (cfg.key -> cfg) //将DeprecatedConfig对象映射成(k, v)形式
    } : _*)//构造了一个Map返回
  }

  /**
   * Maps a current config key to alternate keys that were used in previous version of Spark.
   *
   * The alternates are used in the order defined in this map. If deprecated configs are
   * present in the user's configuration, a warning is logged.
   */
    //有变化的参数，一种只是参数名字变了，还有一种是名字变了，值的表示单位也变了
    //所以AlternateConfig第三个参数默认为null，也可能传入一个函数，表示怎么把旧值转换成新的值
  private val configsWithAlternatives = Map[String, Seq[AlternateConfig]](
    "spark.executor.userClassPathFirst" -> Seq(//因为一个参数可能在多个版本中变来变去，所以使用了Seq
      AlternateConfig("spark.files.userClassPathFirst", "1.3")),
    "spark.history.fs.update.interval" -> Seq(
      AlternateConfig("spark.history.fs.update.interval.seconds", "1.4"),
      AlternateConfig("spark.history.fs.updateInterval", "1.3"),
      AlternateConfig("spark.history.updateInterval", "1.3")),
    "spark.history.fs.cleaner.interval" -> Seq(
      AlternateConfig("spark.history.fs.cleaner.interval.seconds", "1.4")),
    "spark.history.fs.cleaner.maxAge" -> Seq(
      AlternateConfig("spark.history.fs.cleaner.maxAge.seconds", "1.4")),
    "spark.yarn.am.waitTime" -> Seq(
      AlternateConfig("spark.yarn.applicationMaster.waitTries", "1.3",
        // Translate old value to a duration, with 10s wait time per try.
        translation = s => s"${s.toLong * 10}s")),//此时并没有进行转换，只是记录了用于转换的函数
    "spark.reducer.maxSizeInFlight" -> Seq(
      AlternateConfig("spark.reducer.maxMbInFlight", "1.4")),
    "spark.kryoserializer.buffer" ->
        Seq(AlternateConfig("spark.kryoserializer.buffer.mb", "1.4",
          translation = s => s"${(s.toDouble * 1000).toInt}k")),
    "spark.kryoserializer.buffer.max" -> Seq(
      AlternateConfig("spark.kryoserializer.buffer.max.mb", "1.4")),
    "spark.shuffle.file.buffer" -> Seq(
      AlternateConfig("spark.shuffle.file.buffer.kb", "1.4")),
    "spark.executor.logs.rolling.maxSize" -> Seq(
      AlternateConfig("spark.executor.logs.rolling.size.maxBytes", "1.4")),
    "spark.io.compression.snappy.blockSize" -> Seq(
      AlternateConfig("spark.io.compression.snappy.block.size", "1.4")),
    "spark.io.compression.lz4.blockSize" -> Seq(
      AlternateConfig("spark.io.compression.lz4.block.size", "1.4")),
    "spark.rpc.numRetries" -> Seq(
      AlternateConfig("spark.akka.num.retries", "1.4")),
    "spark.rpc.retry.wait" -> Seq(
      AlternateConfig("spark.akka.retry.wait", "1.4")),
    "spark.rpc.askTimeout" -> Seq(
      AlternateConfig("spark.akka.askTimeout", "1.4")),
    "spark.rpc.lookupTimeout" -> Seq(
      AlternateConfig("spark.akka.lookupTimeout", "1.4")),
    "spark.streaming.fileStream.minRememberDuration" -> Seq(
      AlternateConfig("spark.streaming.minRememberDuration", "1.5")),
    "spark.yarn.max.executor.failures" -> Seq(
      AlternateConfig("spark.yarn.max.worker.failures", "1.5")),
    "spark.memory.offHeap.enabled" -> Seq(
      AlternateConfig("spark.unsafe.offHeap", "1.6")),
    "spark.rpc.message.maxSize" -> Seq(
      AlternateConfig("spark.akka.frameSize", "1.6")),
    "spark.yarn.jars" -> Seq(
      AlternateConfig("spark.yarn.jar", "2.0"))
    )

  /**
   * A view of `configsWithAlternatives` that makes it more efficient to look up deprecated
   * config keys.
   *
   * Maps the deprecated config name to a 2-tuple (new config name, alternate config info).
   */
    //第6步
  private val allAlternatives: Map[String, (String, AlternateConfig)] = {
    configsWithAlternatives.keys.flatMap { key =>
      configsWithAlternatives(key).map { cfg => (cfg.key -> (key -> cfg)) }
    }.toMap
  }

  /**
   * Return whether the given config should be passed to an executor on start-up.
   *
   * Certain authentication configs are required from the executor when it connects to
   * the scheduler, while the rest of the spark configs can be inherited from the driver later.
    *
    * executor启动连接scheduler的时候需要的一些安全验证参数在executor启动之前需要传给executor
    * 另外就是关于连接端口也需要executor启动之前就传过去
    * 其他的一些参数可以之后再向driver继承
   */
  def isExecutorStartupConf(name: String): Boolean = {//满足条件其一就返回true
    (name.startsWith("spark.auth") && name != SecurityManager.SPARK_AUTH_SECRET_CONF) ||
    name.startsWith("spark.ssl") ||
    name.startsWith("spark.rpc") ||
    isSparkPortConf(name)//spark相关的端口信息
  }

  /**
   * Return true if the given config matches either `spark.*.port` or `spark.port.*`.
   */
  def isSparkPortConf(name: String): Boolean = {
    (name.startsWith("spark.") && name.endsWith(".port")) || name.startsWith("spark.port.")
  }

  /**
   * Looks for available deprecated keys for the given config option, and return the first
   * value available.
   */
  def getDeprecatedConfig(key: String, conf: SparkConf): Option[String] = {
    configsWithAlternatives.get(key).flatMap { alts =>
      alts.collectFirst { case alt if conf.contains(alt.key) =>
        val value = conf.get(alt.key)
        if (alt.translation != null) alt.translation(value) else value
      }
    }
  }

  /**
   * Logs a warning message if the given config key is deprecated.
   */
  //第4步
  def logDeprecationWarning(key: String): Unit = {
    deprecatedConfigs.get(key).foreach { cfg =>
      logWarning(
        s"The configuration key '$key' has been deprecated as of Spark ${cfg.version} and " +
        s"may be removed in the future. ${cfg.deprecationMessage}")
      return
    }


    allAlternatives.get(key).foreach { case (newKey, cfg) =>
      logWarning(
        s"The configuration key '$key' has been deprecated as of Spark ${cfg.version} and " +
        s"may be removed in the future. Please use the new key '$newKey' instead.")
      return
    }
    if (key.startsWith("spark.akka") || key.startsWith("spark.ssl.akka")) {
      logWarning(
        s"The configuration key $key is not supported any more " +
          s"because Spark doesn't use Akka since 2.0")
    }
  }

  /**
   * Holds information about keys that have been deprecated and do not have a replacement.
   *
   * @param key The deprecated key.
   * @param version Version of Spark where key was deprecated.
   * @param deprecationMessage Message to include in the deprecation warning.
   */
  private case class DeprecatedConfig(
      key: String,
      version: String,
      deprecationMessage: String)

  /**
   * Information about an alternate configuration key that has been deprecated.
   *
   * @param key The deprecated config key.
   * @param version The Spark version in which the key was deprecated.
   * @param translation A translation function for converting old config values into new ones.
   */
  private case class AlternateConfig(
      key: String,
      version: String,
      translation: String => String = null)

}
