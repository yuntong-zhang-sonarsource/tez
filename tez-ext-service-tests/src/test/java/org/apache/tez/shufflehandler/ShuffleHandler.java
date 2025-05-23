/*
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

package org.apache.tez.shufflehandler;

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.google.common.base.Charsets;

import org.apache.tez.common.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.SecureIOUtils;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.tez.common.security.JobTokenIdentifier;
import org.apache.tez.common.security.JobTokenSecretManager;
import org.apache.tez.mapreduce.hadoop.MRConfig;
import org.apache.tez.runtime.library.common.security.SecureShuffleUtils;
import org.apache.tez.runtime.library.common.shuffle.orderedgrouped.ShuffleHeader;
import org.apache.tez.runtime.library.common.sort.impl.TezIndexRecord;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.GlobalEventExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShuffleHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ShuffleHandler.class);

  public static final String SHUFFLE_HANDLER_LOCAL_DIRS = "tez.shuffle.handler.local-dirs";

  // pattern to identify errors related to the client closing the socket early
  // idea borrowed from Netty SslHandler
  private static final Pattern IGNORABLE_ERROR_MESSAGE = Pattern.compile(
      "^.*(?:connection.*reset|connection.*closed|broken.*pipe).*$",
      Pattern.CASE_INSENSITIVE);

  private int port;

  // pipeline items
  private Shuffle SHUFFLE;

  private NioEventLoopGroup bossGroup;
  private NioEventLoopGroup workerGroup;
  private final ChannelGroup accepted = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
  private final Configuration conf;

  private final ConcurrentMap<String, Boolean> registeredApps = new ConcurrentHashMap<String, Boolean>();

  /**
   * Should the shuffle use posix_fadvise calls to manage the OS cache during
   * sendfile
   */
  private final int maxShuffleConnections;

  private Map<String,String> userRsrc;
  private JobTokenSecretManager secretManager;

  public static final String SHUFFLE_PORT_CONFIG_KEY = "tez.shuffle.port";
  public static final int DEFAULT_SHUFFLE_PORT = 15551;

  // TODO Change configs to remove mapreduce references.
  public static final String SHUFFLE_CONNECTION_KEEP_ALIVE_ENABLED =
      "mapreduce.shuffle.connection-keep-alive.enable";
  public static final boolean DEFAULT_SHUFFLE_CONNECTION_KEEP_ALIVE_ENABLED = false;

  public static final String SHUFFLE_CONNECTION_KEEP_ALIVE_TIME_OUT =
      "mapreduce.shuffle.connection-keep-alive.timeout";
  public static final int DEFAULT_SHUFFLE_CONNECTION_KEEP_ALIVE_TIME_OUT = 5; //seconds

  public static final String SHUFFLE_MAPOUTPUT_META_INFO_CACHE_SIZE =
      "mapreduce.shuffle.mapoutput-info.meta.cache.size";
  public static final int DEFAULT_SHUFFLE_MAPOUTPUT_META_INFO_CACHE_SIZE =
      1000;

  public static final String CONNECTION_CLOSE = "close";

  public static final String MAX_SHUFFLE_CONNECTIONS = "mapreduce.shuffle.max.connections";
  public static final int DEFAULT_MAX_SHUFFLE_CONNECTIONS = 0; // 0 implies no limit
  
  public static final String MAX_SHUFFLE_THREADS = "mapreduce.shuffle.max.threads";
  // 0 implies Netty default of 2 * number of available processors
  public static final int DEFAULT_MAX_SHUFFLE_THREADS = 0;
  
  final boolean connectionKeepAliveEnabled;
  final int connectionKeepAliveTimeOut;
  final int mapOutputMetaInfoCacheSize;
  private static final AtomicBoolean started = new AtomicBoolean(false);
  private static final AtomicBoolean initing = new AtomicBoolean(false);
  private static ShuffleHandler INSTANCE;


  public ShuffleHandler(Configuration conf) {
    this.conf = conf;

    maxShuffleConnections = conf.getInt(MAX_SHUFFLE_CONNECTIONS,
        DEFAULT_MAX_SHUFFLE_CONNECTIONS);
    int maxShuffleThreads = conf.getInt(MAX_SHUFFLE_THREADS,
        DEFAULT_MAX_SHUFFLE_THREADS);
    if (maxShuffleThreads == 0) {
      maxShuffleThreads = 2 * Runtime.getRuntime().availableProcessors();
    }

    final String BOSS_THREAD_NAME_PREFIX = "ShuffleHandler Netty Boss #";
    AtomicInteger bossThreadCounter = new AtomicInteger(0);
    bossGroup = new NioEventLoopGroup(maxShuffleThreads, new ThreadFactory() {
      @Override
      public Thread newThread(Runnable r) {
        return new Thread(r, BOSS_THREAD_NAME_PREFIX + bossThreadCounter.incrementAndGet());
      }
    });

    final String WORKER_THREAD_NAME_PREFIX = "ShuffleHandler Netty Worker #";
    AtomicInteger workerThreadCounter = new AtomicInteger(0);
    workerGroup = new NioEventLoopGroup(maxShuffleThreads, new ThreadFactory() {
      @Override
      public Thread newThread(Runnable r) {
        return new Thread(r, WORKER_THREAD_NAME_PREFIX + workerThreadCounter.incrementAndGet());
      }
    });

    connectionKeepAliveEnabled =
        conf.getBoolean(SHUFFLE_CONNECTION_KEEP_ALIVE_ENABLED,
            DEFAULT_SHUFFLE_CONNECTION_KEEP_ALIVE_ENABLED);
    connectionKeepAliveTimeOut =
        Math.max(1, conf.getInt(SHUFFLE_CONNECTION_KEEP_ALIVE_TIME_OUT,
            DEFAULT_SHUFFLE_CONNECTION_KEEP_ALIVE_TIME_OUT));
    mapOutputMetaInfoCacheSize =
        Math.max(1, conf.getInt(SHUFFLE_MAPOUTPUT_META_INFO_CACHE_SIZE,
            DEFAULT_SHUFFLE_MAPOUTPUT_META_INFO_CACHE_SIZE));

    userRsrc = new ConcurrentHashMap<String,String>();
    secretManager = new JobTokenSecretManager(conf);
  }


  public void start() throws Exception {
    ServerBootstrap bootstrap = new ServerBootstrap()
        .channel(NioServerSocketChannel.class)
        .group(bossGroup, workerGroup)
        .localAddress(port);
    initPipeline(bootstrap, conf);
    port = conf.getInt(SHUFFLE_PORT_CONFIG_KEY, DEFAULT_SHUFFLE_PORT);
    Channel ch = bootstrap.bind().sync().channel();
    accepted.add(ch);
    port = ((InetSocketAddress)ch.localAddress()).getPort();
    conf.set(SHUFFLE_PORT_CONFIG_KEY, Integer.toString(port));
    SHUFFLE.setPort(port);
    LOG.info("TezShuffleHandler" + " listening on port " + port);
  }

  private void initPipeline(ServerBootstrap bootstrap, Configuration conf) throws Exception {
    SHUFFLE = getShuffle(conf);

    if (conf.getBoolean(MRConfig.SHUFFLE_SSL_ENABLED_KEY,
        MRConfig.SHUFFLE_SSL_ENABLED_DEFAULT)) {
      throw new UnsupportedOperationException(
          "SSL Shuffle is not currently supported for the test shuffle handler");
    }

    ChannelInitializer<NioSocketChannel> channelInitializer =
        new ChannelInitializer<NioSocketChannel>() {
          @Override
      public void initChannel(NioSocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast("decoder", new HttpRequestDecoder());
        pipeline.addLast("aggregator", new HttpObjectAggregator(1 << 16));
        pipeline.addLast("encoder", new HttpResponseEncoder());
        pipeline.addLast("chunking", new ChunkedWriteHandler());
        pipeline.addLast("shuffle", SHUFFLE);
      }
    };
    bootstrap.childHandler(channelInitializer);
  }

  public static void initializeAndStart(Configuration conf) throws Exception {
    if (!initing.getAndSet(true)) {
      INSTANCE = new ShuffleHandler(conf);
      INSTANCE.start();
      started.set(true);
    }
  }

  public static ShuffleHandler get() {
    Preconditions.checkState(started.get(),
        "ShuffleHandler must be started before invoking started");
    return INSTANCE;
  }

  public int getPort() {
    return port;
  }

  public void registerApplication(String applicationIdString, Token<JobTokenIdentifier> appToken,
                                  String user) {
    Boolean registered = registeredApps.putIfAbsent(applicationIdString, Boolean.valueOf(true));
    if (registered == null) {
      recordJobShuffleInfo(applicationIdString, user, appToken);
    }
  }

  public void unregisterApplication(String applicationIdString) {
    removeJobShuffleInfo(applicationIdString);
  }

  public void stop() throws Exception {
    accepted.close().awaitUninterruptibly(10, TimeUnit.SECONDS);
    if (bossGroup != null) {
      bossGroup.shutdownGracefully();
    }
    if (workerGroup != null) {
      workerGroup.shutdownGracefully();
    }
  }

  protected Shuffle getShuffle(Configuration conf) {
    return new Shuffle(conf);
  }

  private void addJobToken(String appIdString, String user,
      Token<JobTokenIdentifier> jobToken) {
    String jobIdString = appIdString.replace("application", "job");
    userRsrc.put(jobIdString, user);
    secretManager.addTokenForJob(jobIdString, jobToken);
    LOG.info("Added token for " + jobIdString);
  }

  private void recordJobShuffleInfo(String appIdString, String user,
      Token<JobTokenIdentifier> jobToken) {
    addJobToken(appIdString, user, jobToken);
  }

  private void removeJobShuffleInfo(String appIdString) {
    secretManager.removeTokenForJob(appIdString);
    userRsrc.remove(appIdString);
  }

  @Sharable
  class Shuffle extends ChannelInboundHandlerAdapter {

    private final Configuration conf;
    private final IndexCache indexCache;
    private final LocalDirAllocator lDirAlloc =
      new LocalDirAllocator(SHUFFLE_HANDLER_LOCAL_DIRS);
    private int port;

    public Shuffle(Configuration conf) {
      this.conf = conf;
      indexCache = new IndexCache(conf);
      this.port = conf.getInt(SHUFFLE_PORT_CONFIG_KEY, DEFAULT_SHUFFLE_PORT);
    }
    
    public void setPort(int port) {
      this.port = port;
    }

    private List<String> splitMaps(List<String> mapq) {
      if (null == mapq) {
        return null;
      }
      final List<String> ret = new ArrayList<String>();
      for (String s : mapq) {
        Collections.addAll(ret, s.split(","));
      }
      return ret;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx)
        throws Exception {

      if ((maxShuffleConnections > 0) && (accepted.size() >= maxShuffleConnections)) {
        LOG.info(String.format("Current number of shuffle connections (%d) is " +
            "greater than or equal to the max allowed shuffle connections (%d)",
            accepted.size(), maxShuffleConnections));
        ctx.channel().close();
        return;
      }
      accepted.add(ctx.channel());
      super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message)
        throws Exception {
      HttpRequest request = (HttpRequest) message;
      if (request.getMethod() != GET) {
          sendError(ctx, METHOD_NOT_ALLOWED);
          return;
      }
      // Check whether the shuffle version is compatible
      if (!ShuffleHeader.DEFAULT_HTTP_HEADER_NAME.equals(
          request.headers().get(ShuffleHeader.HTTP_HEADER_NAME))
          || !ShuffleHeader.DEFAULT_HTTP_HEADER_VERSION.equals(
              request.headers().get(ShuffleHeader.HTTP_HEADER_VERSION))) {
        sendError(ctx, "Incompatible shuffle request version", BAD_REQUEST);
      }
      final Map<String, List<String>> q = new QueryStringDecoder(request.getUri()).parameters();
      final List<String> keepAliveList = q.get("keepAlive");
      boolean keepAliveParam = false;
      if (keepAliveList != null && keepAliveList.size() == 1) {
        keepAliveParam = Boolean.valueOf(keepAliveList.get(0));
        if (LOG.isDebugEnabled()) {
          LOG.debug("KeepAliveParam : " + keepAliveList
            + " : " + keepAliveParam);
        }
      }
      final List<String> mapIds = splitMaps(q.get("map"));
      final List<String> reduceQ = q.get("reduce");
      final List<String> jobQ = q.get("job");
      if (LOG.isDebugEnabled()) {
        LOG.debug("RECV: " + request.getUri() +
            "\n  mapId: " + mapIds +
            "\n  reduceId: " + reduceQ +
            "\n  jobId: " + jobQ +
            "\n  keepAlive: " + keepAliveParam);
      }

      if (mapIds == null || reduceQ == null || jobQ == null) {
        sendError(ctx, "Required param job, map and reduce", BAD_REQUEST);
        return;
      }
      if (reduceQ.size() != 1 || jobQ.size() != 1) {
        sendError(ctx, "Too many job/reduce parameters", BAD_REQUEST);
        return;
      }
      int reduceId;
      String jobId;
      try {
        reduceId = Integer.parseInt(reduceQ.get(0));
        jobId = jobQ.get(0);
      } catch (NumberFormatException e) {
        sendError(ctx, "Bad reduce parameter", BAD_REQUEST);
        return;
      } catch (IllegalArgumentException e) {
        sendError(ctx, "Bad job parameter", BAD_REQUEST);
        return;
      }
      final String reqUri = request.getUri();
      if (null == reqUri) {
        // TODO? add upstream?
        sendError(ctx, FORBIDDEN);
        return;
      }
      HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
      try {
        verifyRequest(jobId, ctx, request, response,
            new URL("http", "", this.port, reqUri));
      } catch (IOException e) {
        LOG.warn("Shuffle failure ", e);
        sendError(ctx, e.getMessage(), UNAUTHORIZED);
        return;
      }

      Map<String, MapOutputInfo> mapOutputInfoMap =
          new HashMap<String, MapOutputInfo>();
      Channel ch = ctx.channel();
      String user = userRsrc.get(jobId);

      // $x/$user/appcache/$appId/output/$mapId
      // TODO: Once Shuffle is out of NM, this can use MR APIs to convert
      // between App and Job
      String outputBasePathStr = getBaseLocation(jobId, user);

      try {
        populateHeaders(mapIds, outputBasePathStr, user, reduceId, request,
          response, keepAliveParam, mapOutputInfoMap);
      } catch(IOException e) {
        ch.writeAndFlush(response);
        LOG.error("Shuffle error in populating headers :", e);
        String errorMessage = getErrorMessage(e);
        sendError(ctx,errorMessage , INTERNAL_SERVER_ERROR);
        return;
      }
      ch.writeAndFlush(response);
      // TODO refactor the following into the pipeline
      ChannelFuture lastMap = null;
      for (String mapId : mapIds) {
        try {
          MapOutputInfo info = mapOutputInfoMap.get(mapId);
          if (info == null) {
            info = getMapOutputInfo(outputBasePathStr, mapId, reduceId, user);
          }
          lastMap =
              sendMapOutput(ctx, ch, user, mapId,
                reduceId, info);
          if (null == lastMap) {
            sendError(ctx, NOT_FOUND);
            return;
          }
        } catch (IOException e) {
          LOG.error("Shuffle error :", e);
          String errorMessage = getErrorMessage(e);
          sendError(ctx,errorMessage , INTERNAL_SERVER_ERROR);
          return;
        }
      }
      lastMap.addListener(ChannelFutureListener.CLOSE);
    }

    private String getErrorMessage(Throwable t) {
      StringBuffer sb = new StringBuffer(t.getMessage());
      while (t.getCause() != null) {
        sb.append(t.getCause().getMessage());
        t = t.getCause();
      }
      return sb.toString();
    }

    private final String USERCACHE_CONSTANT = "usercache";
    private final String APPCACHE_CONSTANT = "appcache";

    private String getBaseLocation(String jobIdString, String user) {
      String parts[] = jobIdString.split("_");
      Preconditions.checkArgument(parts.length == 3, "Invalid jobId. Expecting 3 parts");
      final ApplicationId appID =
          ApplicationId.newInstance(Long.parseLong(parts[1]), Integer.parseInt(parts[2]));
      final String baseStr =
          USERCACHE_CONSTANT + "/" + user + "/"
              + APPCACHE_CONSTANT + "/"
              + ConverterUtils.toString(appID) + "/output" + "/";
      return baseStr;
    }

    protected MapOutputInfo getMapOutputInfo(String base, String mapId,
        int reduce, String user) throws IOException {
      // Index file
      Path indexFileName =
          lDirAlloc.getLocalPathToRead(base + "/file.out.index", conf);
      TezIndexRecord info =
          indexCache.getIndexInformation(mapId, reduce, indexFileName, user);

      Path mapOutputFileName =
          lDirAlloc.getLocalPathToRead(base + "/file.out", conf);
      if (LOG.isDebugEnabled()) {
        LOG.debug(base + " : " + mapOutputFileName + " : " + indexFileName);
      }
      MapOutputInfo outputInfo = new MapOutputInfo(mapOutputFileName, info);
      return outputInfo;
    }

    protected void populateHeaders(List<String> mapIds, String outputBaseStr,
        String user, int reduce, HttpRequest request, HttpResponse response,
        boolean keepAliveParam, Map<String, MapOutputInfo> mapOutputInfoMap)
        throws IOException {

      long contentLength = 0;
      for (String mapId : mapIds) {
        String base = outputBaseStr + mapId;
        MapOutputInfo outputInfo = getMapOutputInfo(base, mapId, reduce, user);
        if (mapOutputInfoMap.size() < mapOutputMetaInfoCacheSize) {
          mapOutputInfoMap.put(mapId, outputInfo);
        }
        // Index file
        Path indexFileName =
            lDirAlloc.getLocalPathToRead(base + "/file.out.index", conf);
        TezIndexRecord info =
            indexCache.getIndexInformation(mapId, reduce, indexFileName, user);
        ShuffleHeader header =
            new ShuffleHeader(mapId, info.getPartLength(), info.getRawLength(), reduce);
        DataOutputBuffer dob = new DataOutputBuffer();
        header.write(dob);

        contentLength += info.getPartLength();
        contentLength += dob.getLength();
      }

      // Now set the response headers.
      setResponseHeaders(response, keepAliveParam, contentLength);
    }

    protected void setResponseHeaders(HttpResponse response,
        boolean keepAliveParam, long contentLength) {
      if (!connectionKeepAliveEnabled && !keepAliveParam) {
        LOG.info("Setting connection close header...");
        response.headers().set(HttpHeaders.Names.CONNECTION, CONNECTION_CLOSE);
      } else {
        response.headers().set(HttpHeaders.Names.CONTENT_LENGTH,
          String.valueOf(contentLength));
        response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        response.headers().set(HttpHeaders.Values.KEEP_ALIVE, "timeout="
            + connectionKeepAliveTimeOut);
        LOG.info("Content Length in shuffle : " + contentLength);
      }
    }

    class MapOutputInfo {
      final Path mapOutputFileName;
      final TezIndexRecord indexRecord;

      MapOutputInfo(Path mapOutputFileName, TezIndexRecord indexRecord) {
        this.mapOutputFileName = mapOutputFileName;
        this.indexRecord = indexRecord;
      }
    }

    protected void verifyRequest(String appid, ChannelHandlerContext ctx,
        HttpRequest request, HttpResponse response, URL requestUri)
        throws IOException {
      SecretKey tokenSecret = secretManager.retrieveTokenSecret(appid);
      if (null == tokenSecret) {
        LOG.info("Request for unknown token " + appid);
        throw new IOException("could not find jobid");
      }
      // string to encrypt
      String enc_str = SecureShuffleUtils.buildMsgFrom(requestUri);
      // hash from the fetcher
      String urlHashStr =
        request.headers().get(SecureShuffleUtils.HTTP_HEADER_URL_HASH);
      if (urlHashStr == null) {
        LOG.info("Missing header hash for " + appid);
        throw new IOException("fetcher cannot be authenticated");
      }
      if (LOG.isDebugEnabled()) {
        int len = urlHashStr.length();
        LOG.debug("verifying request. enc_str=" + enc_str + "; hash=..." +
            urlHashStr.substring(len-len/2, len-1));
      }
      // verify - throws exception
      SecureShuffleUtils.verifyReply(urlHashStr, enc_str, tokenSecret);
      // verification passed - encode the reply
      String reply =
        SecureShuffleUtils.generateHash(urlHashStr.getBytes(Charsets.UTF_8), 
            tokenSecret);
      response.headers().set(SecureShuffleUtils.HTTP_HEADER_REPLY_URL_HASH, reply);
      // Put shuffle version into http header
      response.headers().set(ShuffleHeader.HTTP_HEADER_NAME,
          ShuffleHeader.DEFAULT_HTTP_HEADER_NAME);
      response.headers().set(ShuffleHeader.HTTP_HEADER_VERSION,
          ShuffleHeader.DEFAULT_HTTP_HEADER_VERSION);
      if (LOG.isDebugEnabled()) {
        int len = reply.length();
        LOG.debug("Fetcher request verfied. enc_str=" + enc_str + ";reply=" +
            reply.substring(len-len/2, len-1));
      }
    }

    protected ChannelFuture sendMapOutput(ChannelHandlerContext ctx, Channel ch,
        String user, String mapId, int reduce, MapOutputInfo mapOutputInfo)
        throws IOException {
      final TezIndexRecord info = mapOutputInfo.indexRecord;
      final ShuffleHeader header =
        new ShuffleHeader(mapId, info.getPartLength(), info.getRawLength(), reduce);
      final DataOutputBuffer dob = new DataOutputBuffer();
      header.write(dob);
      ch.writeAndFlush(wrappedBuffer(dob.getData(), 0, dob.getLength()));
      final File spillfile =
          new File(mapOutputInfo.mapOutputFileName.toString());
      RandomAccessFile spill;
      try {
        spill = SecureIOUtils.openForRandomRead(spillfile, "r", user, null);
      } catch (FileNotFoundException e) {
        LOG.info(spillfile + " not found");
        return null;
      }
      ChannelFuture writeFuture;
      final DefaultFileRegion partition =
          new DefaultFileRegion(spill.getChannel(), info.getStartOffset(), info.getPartLength());
      writeFuture = ch.writeAndFlush(partition);
      return writeFuture;
    }

    protected void sendError(ChannelHandlerContext ctx,
        HttpResponseStatus status) {
      sendError(ctx, "", status);
    }

    protected void sendError(ChannelHandlerContext ctx, String message,
        HttpResponseStatus status) {
      FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status);
      response.headers().set(CONTENT_TYPE, "text/plain; charset=UTF-8");
      // Put shuffle version into http header
      response.headers().set(ShuffleHeader.HTTP_HEADER_NAME,
          ShuffleHeader.DEFAULT_HTTP_HEADER_NAME);
      response.headers().set(ShuffleHeader.HTTP_HEADER_VERSION,
          ShuffleHeader.DEFAULT_HTTP_HEADER_VERSION);
      response.content().writeBytes(Unpooled.copiedBuffer(message, CharsetUtil.UTF_8));

      // Close the connection as soon as the error message is sent.
      ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
        throws Exception {
      if (cause instanceof TooLongFrameException) {
        sendError(ctx, BAD_REQUEST);
        return;
      } else if (cause instanceof IOException) {
        if (cause instanceof ClosedChannelException) {
          LOG.debug("Ignoring closed channel error", cause);
          return;
        }
        String message = String.valueOf(cause.getMessage());
        if (IGNORABLE_ERROR_MESSAGE.matcher(message).matches()) {
          LOG.debug("Ignoring client socket close", cause);
          return;
        }
      }

      LOG.error("Shuffle error: ", cause);
      if (ctx.channel().isActive()) {
        LOG.error("Shuffle error", cause);
        sendError(ctx, INTERNAL_SERVER_ERROR);
      }
    }
  }
}
