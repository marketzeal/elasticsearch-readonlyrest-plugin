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
package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.requestcontext.RequestContext;

import java.util.concurrent.CompletableFuture;

public class AsyncRuleAdapter extends AsyncRule {

  private final SyncRule underlying;

  public AsyncRuleAdapter(SyncRule underlying) {
    this.underlying = underlying;
  }

  public static AsyncRule wrap(SyncRule rule) {
    return new AsyncRuleAdapter(rule);
  }

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {
    return CompletableFuture.completedFuture(underlying.match(rc));
  }

  @Override
  public boolean onResponse(BlockExitResult result, RequestContext rc, ActionRequest ar, ActionResponse response) {
    return underlying.onResponse(result, rc, ar, response);
  }

  @Override
  public String getKey() {
    return underlying.getKey();
  }

  public SyncRule getUnderlying() {
    return underlying;
  }
}
