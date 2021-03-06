/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.elasticsearch.percolator;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.memory.ExtendedMemoryIndex;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.CloseableThreadLocal;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.ElasticSearchParseException;
import org.elasticsearch.action.percolate.PercolateShardRequest;
import org.elasticsearch.action.percolate.PercolateShardResponse;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.lucene.search.XConstantScoreQuery;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.cache.IndexCache;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.fielddata.IndexFieldDataService;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.internal.UidFieldMapper;
import org.elasticsearch.index.percolator.stats.ShardPercolateService;
import org.elasticsearch.index.service.IndexService;
import org.elasticsearch.index.shard.service.IndexShard;
import org.elasticsearch.indices.IndicesService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.elasticsearch.index.mapper.SourceToParse.source;
import static org.elasticsearch.percolator.QueryCollector.*;

/**
 */
public class PercolatorService extends AbstractComponent {

    private final CloseableThreadLocal<MemoryIndex> cache;
    private final IndicesService indicesService;

    @Inject
    public PercolatorService(Settings settings, IndicesService indicesService) {
        super(settings);
        this.indicesService = indicesService;
        final long maxReuseBytes = settings.getAsBytesSize("indices.memory.memory_index.size_per_thread", new ByteSizeValue(1, ByteSizeUnit.MB)).bytes();
        cache = new CloseableThreadLocal<MemoryIndex>() {
            @Override
            protected MemoryIndex initialValue() {
                return new ExtendedMemoryIndex(false, maxReuseBytes);
            }
        };
    }

    public PercolateShardResponse matchPercolate(final PercolateShardRequest request) {
        return preparePercolate(request, new PercolateAction() {
            @Override
            public PercolateShardResponse doPercolateAction(PercolateContext context) {
                final List<Text> matches;
                long count = 0;
                if (context.query == null) {
                    matches = new ArrayList<Text>();
                    Lucene.ExistsCollector collector = new Lucene.ExistsCollector();
                    for (Map.Entry<Text, Query> entry : context.percolateQueries.entrySet()) {
                        collector.reset();
                        try {
                            context.docSearcher.search(entry.getValue(), collector);
                        } catch (IOException e) {
                            logger.warn("[" + entry.getKey() + "] failed to execute query", e);
                        }

                        if (collector.exists()) {
                            if (!context.limit) {
                                matches.add(entry.getKey());
                            } else if (count < context.size) {
                                matches.add(entry.getKey());
                            }
                            count++;
                        }
                    }
                } else {
                    Engine.Searcher percolatorSearcher = context.indexShard.searcher();
                    try {
                        Match match = match(logger, context.percolateQueries, context.docSearcher, context.fieldDataService, context);
                        percolatorSearcher.searcher().search(context.query, match);
                        matches = match.matches();
                        count = match.counter();
                    } catch (IOException e) {
                        logger.debug("failed to execute", e);
                        throw new PercolateException(context.indexShard.shardId(), "failed to execute", e);
                    } finally {
                        percolatorSearcher.release();
                    }
                }
                return new PercolateShardResponse(matches.toArray(new Text[matches.size()]), count, context, request.index(), request.shardId());
            }
        });
    }

    public PercolateShardResponse countPercolate(final PercolateShardRequest request) {
        return preparePercolate(request, new PercolateAction() {
            @Override
            public PercolateShardResponse doPercolateAction(PercolateContext context) {
                long count = 0;
                if (context.query == null) {
                    Lucene.ExistsCollector collector = new Lucene.ExistsCollector();
                    for (Map.Entry<Text, Query> entry : context.percolateQueries.entrySet()) {
                        collector.reset();
                        try {
                            context.docSearcher.search(entry.getValue(), collector);
                        } catch (IOException e) {
                            logger.warn("[" + entry.getKey() + "] failed to execute query", e);
                        }

                        if (collector.exists()) {
                            count++;
                        }
                    }
                } else {
                    Engine.Searcher percolatorSearcher = context.indexShard.searcher();
                    try {
                        Count countCollector = count(logger, context.percolateQueries, context.docSearcher, context.fieldDataService);
                        percolatorSearcher.searcher().search(context.query, countCollector);
                        count = countCollector.counter();
                    } catch (IOException e) {
                        logger.warn("failed to execute", e);
                    } finally {
                        percolatorSearcher.release();
                    }
                }
                return new PercolateShardResponse(count, context, request.index(), request.shardId());
            }
        });
    }

    private PercolateShardResponse preparePercolate(PercolateShardRequest request, PercolateAction action) {
        IndexService percolateIndexService = indicesService.indexServiceSafe(request.index());
        IndexShard indexShard = percolateIndexService.shardSafe(request.shardId());

        ShardPercolateService shardPercolateService = indexShard.shardPercolateService();
        shardPercolateService.prePercolate();
        long startTime = System.nanoTime();
        try {
            ConcurrentMap<Text, Query> percolateQueries = indexShard.percolateRegistry().percolateQueries();
            if (percolateQueries.isEmpty()) {
                return new PercolateShardResponse(request.index(), request.shardId());
            }

            final PercolateContext context = new PercolateContext();
            context.percolateQueries = percolateQueries;
            context.indexShard = indexShard;
            ParsedDocument parsedDocument = parsePercolate(percolateIndexService, request, context);
            if (request.docSource() != null && request.docSource().length() != 0) {
                parsedDocument = parseFetchedDoc(request.docSource(), percolateIndexService, request.documentType());
            } else if (parsedDocument == null) {
                throw new ElasticSearchParseException("No doc to percolate in the request");
            }

            if (context.size < 0) {
                context.size = 0;
            }

            // first, parse the source doc into a MemoryIndex
            final MemoryIndex memoryIndex = cache.get();
            try {
                // TODO: This means percolation does not support nested docs...
                // So look into: ByteBufferDirectory
                for (IndexableField field : parsedDocument.rootDoc().getFields()) {
                    if (!field.fieldType().indexed()) {
                        continue;
                    }
                    // no need to index the UID field
                    if (field.name().equals(UidFieldMapper.NAME)) {
                        continue;
                    }
                    TokenStream tokenStream;
                    try {
                        tokenStream = field.tokenStream(parsedDocument.analyzer());
                        if (tokenStream != null) {
                            memoryIndex.addField(field.name(), tokenStream, field.boost());
                        }
                    } catch (IOException e) {
                        throw new ElasticSearchException("Failed to create token stream", e);
                    }
                }

                context.docSearcher = memoryIndex.createSearcher();
                context.fieldDataService = percolateIndexService.fieldData();
                IndexCache indexCache = percolateIndexService.cache();
                try {
                    return action.doPercolateAction(context);
                } finally {
                    // explicitly clear the reader, since we can only register on callback on SegmentReader
                    indexCache.clear(context.docSearcher.getIndexReader());
                    context.fieldDataService.clear(context.docSearcher.getIndexReader());
                }
            } finally {
                memoryIndex.reset();
            }
        } finally {
            shardPercolateService.postPercolate(System.nanoTime() - startTime);
        }
    }

    private ParsedDocument parsePercolate(IndexService documentIndexService, PercolateShardRequest request, PercolateContext context) throws ElasticSearchException {
        BytesReference source = request.source();
        if (source == null || source.length() == 0) {
            return null;
        }

        ParsedDocument doc = null;
        XContentParser parser = null;
        try {
            parser = XContentFactory.xContent(source).createParser(source);
            String currentFieldName = null;
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                    // we need to check the "doc" here, so the next token will be START_OBJECT which is
                    // the actual document starting
                    if ("doc".equals(currentFieldName)) {
                        if (doc != null) {
                            throw new ElasticSearchParseException("Either specify doc or get, not both");
                        }

                        MapperService mapperService = documentIndexService.mapperService();
                        DocumentMapper docMapper = mapperService.documentMapperWithAutoCreate(request.documentType());
                        doc = docMapper.parse(source(parser).type(request.documentType()).flyweight(true));
                    }
                } else if (token == XContentParser.Token.START_OBJECT) {
                    if ("query".equals(currentFieldName)) {
                        if (context.query != null) {
                            throw new ElasticSearchParseException("Either specify query or filter, not both");
                        }
                        context.query = documentIndexService.queryParserService().parse(parser).query();
                    } else if ("filter".equals(currentFieldName)) {
                        if (context.query != null) {
                            throw new ElasticSearchParseException("Either specify query or filter, not both");
                        }
                        Filter filter = documentIndexService.queryParserService().parseInnerFilter(parser).filter();
                        context.query = new XConstantScoreQuery(filter);
                    }
                } else if (token.isValue()) {
                    if ("size".equals(currentFieldName)) {
                        context.limit = true;
                        context.size = parser.intValue();
                        if (context.size < 0) {
                            throw new ElasticSearchParseException("size is set to [" + context.size + "] and is expected to be higher or equal to 0");
                        }
                    }
                } else if (token == null) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new ElasticSearchParseException("failed to parse request", e);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }

        return doc;
    }

    private ParsedDocument parseFetchedDoc(BytesReference fetchedDoc, IndexService documentIndexService, String type) {
        ParsedDocument doc = null;
        XContentParser parser = null;
        try {
            parser = XContentFactory.xContent(fetchedDoc).createParser(fetchedDoc);
            MapperService mapperService = documentIndexService.mapperService();
            DocumentMapper docMapper = mapperService.documentMapperWithAutoCreate(type);
            doc = docMapper.parse(source(parser).type(type).flyweight(true));
        } catch (IOException e) {
            throw new ElasticSearchParseException("failed to parse request", e);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }

        if (doc == null) {
            throw new ElasticSearchParseException("No doc to percolate in the request");
        }

        return doc;
    }

    public void close() {
        cache.close();
    }

    interface PercolateAction {

        PercolateShardResponse doPercolateAction(PercolateContext context);

    }

    public class PercolateContext {

        public boolean limit;
        public int size;

        Query query;
        ConcurrentMap<Text, Query> percolateQueries;
        IndexSearcher docSearcher;
        IndexShard indexShard;
        IndexFieldDataService fieldDataService;

    }

    public static final class Constants {

        public static final String TYPE_NAME = "_percolator";

    }

}
