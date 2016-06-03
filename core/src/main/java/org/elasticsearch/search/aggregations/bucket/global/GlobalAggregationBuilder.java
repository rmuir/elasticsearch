/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.bucket.global;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.search.aggregations.AbstractAggregationBuilder;
import org.elasticsearch.search.aggregations.AggregatorFactories.Builder;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.support.AggregationContext;

import java.io.IOException;

public class GlobalAggregationBuilder extends AbstractAggregationBuilder<GlobalAggregationBuilder> {
    public static final String NAME = InternalGlobal.TYPE.name();
    public static final ParseField AGGREGATION_NAME_FIELD = new ParseField(NAME);

    public GlobalAggregationBuilder(String name) {
        super(name, InternalGlobal.TYPE);
    }

    /**
     * Read from a stream.
     */
    public GlobalAggregationBuilder(StreamInput in) throws IOException {
        super(in, InternalGlobal.TYPE);
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        // Nothing to write
    }

    @Override
    protected AggregatorFactory<?> doBuild(AggregationContext context, AggregatorFactory<?> parent, Builder subFactoriesBuilder)
            throws IOException {
        return new GlobalAggregatorFactory(name, type, context, parent, subFactoriesBuilder, metaData);
    }

    @Override
    protected XContentBuilder internalXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.endObject();
        return builder;
    }

    public static GlobalAggregationBuilder parse(String aggregationName, QueryParseContext context) throws IOException {
        context.parser().nextToken();
        return new GlobalAggregationBuilder(aggregationName);
    }

    @Override
    protected boolean doEquals(Object obj) {
        return true;
    }

    @Override
    protected int doHashCode() {
        return 0;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }
}
