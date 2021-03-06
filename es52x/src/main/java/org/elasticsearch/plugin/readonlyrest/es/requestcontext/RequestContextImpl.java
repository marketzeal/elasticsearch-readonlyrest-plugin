/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package org.elasticsearch.plugin.readonlyrest.es.requestcontext;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.LoggerShim;
import org.elasticsearch.plugin.readonlyrest.acl.BlockHistory;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.domain.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.es.ThreadRepo;
import org.elasticsearch.plugin.readonlyrest.requestcontext.IndicesRequestContext;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RCUtils;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.requestcontext.Transactional;
import org.elasticsearch.plugin.readonlyrest.requestcontext.VariablesManager;
import org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.threadpool.ThreadPool;

import java.net.InetSocketAddress;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class RequestContextImpl extends RequestContext implements IndicesRequestContext {

  private final LoggerShim logger;
  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;
  private final String id;
  private final Long taskId;
  private final Map<String, String> requestHeaders;
  private final ClusterService clusterService;
  private final ESContext context;
  private final Transactional<Set<String>> indices;
  private final Transactional<Map<String, String>> responseHeaders;
  private final Date timestamp;
  private final VariablesManager variablesManager;
  private String content = null;
  private Set<BlockHistory> history = Sets.newHashSet();
  private boolean doesInvolveIndices = false;
  private Transactional<Optional<LoggedUser>> loggedInUser;


  public RequestContextImpl(RestRequest request, String action, ActionRequest actionRequest,
                            ClusterService clusterService, ThreadPool threadPool, ESContext context) {
    super("rc", context);
    this.timestamp = new Date();
    this.logger = context.logger(getClass());
    this.request = request;
    this.action = action;
    this.actionRequest = actionRequest;
    this.clusterService = clusterService;
    this.context = context;
    String tmpID = request.hashCode() + "-" + actionRequest.hashCode();
    Long taskId = ThreadRepo.taskId.get();
    if (taskId != null) {
      this.id = tmpID + "#" + taskId;
      ThreadRepo.taskId.remove();
      this.taskId = taskId;
    }
    else {
      this.id = tmpID;
      this.taskId = null;
    }

    final Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    request.headers().forEach(k -> {
      h.put(k.getKey(), k.getValue());
    });

    this.requestHeaders = h;

    this.responseHeaders = new Transactional<Map<String, String>>("rc-resp-headers", context) {
      @Override
      public Map<String, String> initialize() {
        return Maps.newHashMap();
      }

      @Override
      public Map<String, String> copy(Map<String, String> initial) {
        return Maps.newHashMap(initial);
      }

      @Override
      public void onCommit(Map<String, String> hMap) {
        hMap.keySet().forEach(k -> threadPool.getThreadContext().addResponseHeader(k, hMap.get(k)));
      }
    };

    this.loggedInUser = new Transactional<Optional<LoggedUser>>("rc-loggedin-user", context) {
      @Override
      public Optional<LoggedUser> initialize() {
        return Optional.empty();
      }

      @Override
      public Optional<LoggedUser> copy(Optional<LoggedUser> initial) {
        return initial.map((u) -> new LoggedUser(u.getId()));
      }

      @Override
      public void onCommit(Optional<LoggedUser> value) {
        value.ifPresent(loggedUser -> {
          Map<String, String> theMap = responseHeaders.get();
          theMap.put("X-RR-User", loggedUser.getId());
          responseHeaders.mutate(theMap);
        });

      }
    };

    variablesManager = new VariablesManager(h, this, context);

    doesInvolveIndices = actionRequest instanceof IndicesRequest || actionRequest instanceof CompositeIndicesRequest;

    indices = RCTransactionalIndices.mkInstance(this, context);

    // If we get to commit this transaction, put this header.
    delay(() -> loggedInUser.get().ifPresent(loggedUser -> setResponseHeader("X-RR-User", loggedUser.getId())));

    // Register transactional values to the main queue
    responseHeaders.delegateTo(this);
    loggedInUser.delegateTo(this);
    indices.delegateTo(this);
  }

  public LoggerShim getLogger() {
    return logger;
  }

  public Set<BlockHistory> getHistory() {
    return history;
  }

  public void addToHistory(Block block, Set<RuleExitResult> results) {
    BlockHistory blockHistory = new BlockHistory(block.getName(), results);
    history.add(blockHistory);
  }

  @Override
  public String getClusterUUID() {
    return clusterService.state().stateUUID();
  }

  @Override
  public String getNodeUUID() {
    return clusterService.state().nodes().getLocalNodeId();
  }

  ActionRequest getUnderlyingRequest() {
    return actionRequest;
  }

  public String getId() {
    return id;
  }

  @Override
  public Long getTaskId() {
    return taskId;
  }

  public boolean involvesIndices() {
    return doesInvolveIndices;
  }

  public Boolean isReadRequest() {
    if (actionRequest.getClass().isAssignableFrom(WriteRequest.class)) {
      return false;
    }
    return RCUtils.isReadRequest(action);
  }

  public String getRemoteAddress() {
    String remoteHost = ((InetSocketAddress) request.getRemoteAddress()).getAddress().getHostAddress();
    // Make sure we recognize localhost even when IPV6 is involved
    if (RCUtils.isLocalHost(remoteHost)) {
      remoteHost = RCUtils.LOCALHOST;
    }
    return remoteHost;
  }

  public String getContent() {
    if (content == null) {
      try {
        content = request.content().utf8ToString();
      } catch (Exception e) {
        content = "";
      }
    }
    return content;
  }


  public String getType() {
    return actionRequest.getClass().getSimpleName();
  }

  public Optional<String> resolveVariable(String original) {
    return variablesManager.apply(original);

  }

  public Set<String> getAllIndicesAndAliases() {
    return clusterService.state().metaData().getAliasAndIndexLookup().keySet();
  }

  public Set<String> getIndexMetadata(String s) {
    SortedMap<String, AliasOrIndex> lookup = clusterService.state().metaData().getAliasAndIndexLookup();
    return lookup.get(s).getIndices().stream().map(IndexMetaData::getIndexUUID).collect(Collectors.toSet());
  }

  public HttpMethod getMethod() {
    switch (request.method()) {
      case GET:
        return HttpMethod.GET;
      case POST:
        return HttpMethod.POST;
      case PUT:
        return HttpMethod.PUT;
      case DELETE:
        return HttpMethod.DELETE;
      case OPTIONS:
        return HttpMethod.OPTIONS;
      case HEAD:
        return HttpMethod.HEAD;
      default:
        throw context.rorException("Unknown/unsupported http method");
    }
  }

  public Set<String> getExpandedIndices() {
    return getExpandedIndices(indices.getInitial());
  }

  public Set<String> getExpandedIndices(Set<String> ixsSet) {
    if (doesInvolveIndices) {
      //     Index[] i = indexResolver.concreteIndices(clusterService.state(), IndicesOptions.lenientExpandOpen(), "a");
      //      String[] ixs = ixsSet.toArray(new String[ixsSet.size()]);
      //      String[] concreteIdxNames = indexResolver.concreteIndexNames(
      //          clusterService.state(),
      //          IndicesOptions.lenientExpandOpen(), ixs
      //      );
      //      return Sets.newHashSet(concreteIdxNames);
      return new MatcherWithWildcards(ixsSet).filter(getAllIndicesAndAliases());
    }
    throw new ElasticsearchException("Cannot get expanded indices of a non-index request");
  }

  public Set<String> getIndices() {
    if (!doesInvolveIndices) {
      throw context.rorException("cannot get indices of a request that doesn't involve indices" + this);
    }
    return indices.getInitial();
  }

  public void setIndices(final Set<String> newIndices) {
    if (!doesInvolveIndices) {
      throw context.rorException("cannot set indices of a request that doesn't involve indices: " + this);
    }

    if (newIndices.size() == 0) {
      if (isReadRequest()) {
        throw new ElasticsearchException(
          "Attempted to set indices from [" + Joiner.on(",").join(indices.getInitial()) +
            "] toempty set." +
            ", probably your request matched no index, or was rewritten to nonexistentindices (which would expand to empty set).");
      }
      else {
        throw new ElasticsearchException(
          "Attempted to set indices from [" + Joiner.on(",").join(indices.getInitial()) +
            "] to empty set. " + "In ES, specifying no index is the same as full access, therefore this requestis forbidden.");
      }
    }

    if (isReadRequest()) {
      Set<String> expanded = getExpandedIndices(newIndices);

      // When an index don't expand into one or more indices, it means it does not exist. This is fine.
      if (expanded.isEmpty()) {
        expanded = newIndices;
      }
      indices.mutate(expanded);
    }
    else {
      indices.mutate(newIndices);
    }

  }

  public Boolean hasSubRequests() {
    return !SubRequestContext.extractNativeSubrequests(actionRequest).isEmpty();
  }


  public void setResponseHeader(String name, String value) {
    Map<String, String> oldMap = responseHeaders.get();
    oldMap.put(name, value);
    responseHeaders.mutate(oldMap);
  }

  public Integer scanSubRequests(final ReflecUtils.CheckedFunction<IndicesRequestContext, Optional<IndicesRequestContext>> replacer) {

    List<Object> subRequests = SubRequestContext.extractNativeSubrequests(actionRequest);

    logger.debug("found " + subRequests.size() + " subrequests");

    // Composite request #TODO should we really prevent this?
    if (!doesInvolveIndices) {
      throw context.rorException("cannot replace indices of a composite request that doesn't involve indices: " + this);
    }

    Iterator<Object> it = subRequests.iterator();
    while (it.hasNext()) {
      IndicesRequestContext i = new SubRequestContext(this, it.next(), context);
      final Optional<IndicesRequestContext> mutatedSubReqO;

      // Empty optional = remove sub-request from the native list
      try {
        mutatedSubReqO = replacer.apply(i);
        if (!mutatedSubReqO.isPresent()) {
          it.remove();
          continue;
        }
      } catch (IllegalAccessException e) {
        e.printStackTrace();
        throw new ElasticsearchSecurityException("error gathering indices to be replaced in sub-request " + i, e);
      }
      i = mutatedSubReqO.get();

      // We are letting this pass, so let's commit it when we commit the sub-request.
      i.delegateTo(this);

      if (!i.getIndices().equals(i.getIndices())) {
        i.setIndices(i.getIndices());
      }
    }
    return subRequests.size();
  }

  public Map<String, String> getHeaders() {
    return this.requestHeaders;
  }

  public String getUri() {
    return request.uri();
  }

  public String getAction() {
    return action;
  }

  public Optional<LoggedUser> getLoggedInUser() {
    return loggedInUser.get();
  }

  public void setLoggedInUser(LoggedUser user) {
    loggedInUser.mutate(Optional.of(user));
  }

  @Override
  public Date getTimestamp() {
    return timestamp;
  }

  public boolean isDebug() {
    return logger.isDebugEnabled();
  }

  public Set<String> getTransientIndices() {
    return indices.get();
  }
}
