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

package org.elasticsearch.index.fielddata.plain;

import org.apache.lucene.index.*;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.index.fielddata.AtomicFieldData;
import org.elasticsearch.index.fielddata.BytesValues;
import org.elasticsearch.index.fielddata.ordinals.Ordinals;

import java.io.IOException;

/**
 * {@link AtomicFieldData} impl based on Lucene's {@link SortedSetDocValues}.
 * <p><b>Implementation note</b>: Lucene's ordinal for unset values is -1 whereas Elasticsearch's is 0, this is why there are all
 * these +1 to translate from Lucene's ordinals to ES's.
 */
abstract class SortedSetDVAtomicFieldData {

    private final AtomicReader reader;
    private final String field;
    private final boolean multiValued;
    private final long valueCount;

    SortedSetDVAtomicFieldData(AtomicReader reader, String field) {
        this.reader = reader;
        this.field = field;
        SortedSetDocValues dv = getValuesNoException(reader, field);
        this.multiValued = DocValues.unwrapSingleton(dv) == null;
        this.valueCount = dv.getValueCount();
    }

    public boolean isMultiValued() {
        return multiValued;
    }

    public long getNumberUniqueValues() {
        return valueCount;
    }

    public long ramBytesUsed() {
        // There is no API to access memory usage per-field and RamUsageEstimator can't help since there are often references
        // from a per-field instance to all other instances handled by the same format
        return -1L;
    }

    public void close() {
        // no-op
    }

    public org.elasticsearch.index.fielddata.BytesValues.WithOrdinals getBytesValues() {
        final SortedSetDocValues values = getValuesNoException(reader, field);
        return new SortedSetValues(reader, field, values, valueCount, multiValued);
    }

    public TermsEnum getTermsEnum() {
        return getValuesNoException(reader, field).termsEnum();
    }

    private static SortedSetDocValues getValuesNoException(AtomicReader reader, String field) {
        try {
            return DocValues.getSortedSet(reader, field);
        } catch (IOException e) {
            throw new ElasticsearchIllegalStateException("Couldn't load doc values", e);
        }
    }

    private final static class SortedSetValues extends BytesValues.WithOrdinals {

        protected final SortedSetDocValues values;

        SortedSetValues(AtomicReader reader, String field, SortedSetDocValues values, long valueCount, boolean multiValued) {
            super(values instanceof RandomAccessOrds ? 
                    new RandomAccessSortedSetDocs(new SortedSetOrdinals(reader, field, valueCount, multiValued), (RandomAccessOrds) values) :
                    new SortedSetDocs(new SortedSetOrdinals(reader, field, valueCount, multiValued), values));
            this.values = values;
        }

        @Override
        public BytesRef getValueByOrd(long ord) {
            assert ord != Ordinals.MISSING_ORDINAL;
            return scratch = values.lookupOrd(ord);
        }

        @Override
        public BytesRef nextValue() {
            return scratch = values.lookupOrd(ordinals.nextOrd());
        }
    }

    private final static class SortedSetOrdinals implements Ordinals {

        // We don't store SortedSetDocValues as a member because Ordinals must be thread-safe
        private final AtomicReader reader;
        private final String field;
        private final long maxOrd;
        private final boolean multiValued;

        public SortedSetOrdinals(AtomicReader reader, String field, long numOrds, boolean multiValued) {
            super();
            this.reader = reader;
            this.field = field;
            this.maxOrd = numOrds;
            this.multiValued = multiValued;
        }

        @Override
        public long ramBytesUsed() {
            // Ordinals can't be distinguished from the atomic field data instance
            return -1;
        }

        @Override
        public boolean isMultiValued() {
            return multiValued;
        }

        @Override
        public long getMaxOrd() {
            return maxOrd;
        }

        @Override
        public Docs ordinals() {
            final SortedSetDocValues values = getValuesNoException(reader, field);
            assert values.getValueCount() == maxOrd;
            if (values instanceof RandomAccessOrds) {
                return new RandomAccessSortedSetDocs(this, (RandomAccessOrds) values);
            } else {
                return new SortedSetDocs(this, values);
            }
        }

    }

    private final static class SortedSetDocs extends Ordinals.AbstractDocs {

        private final SortedSetDocValues values;
        private long[] ords;
        private int ordIndex = Integer.MAX_VALUE;
        private long currentOrdinal = -1;

        SortedSetDocs(SortedSetOrdinals ordinals, SortedSetDocValues values) {
            super(ordinals);
            this.values = values;
            ords = new long[0];
        }

        @Override
        public long getOrd(int docId) {
            values.setDocument(docId);
            return currentOrdinal = values.nextOrd();
        }

        @Override
        public long nextOrd() {
            assert ordIndex < ords.length;
            return currentOrdinal = ords[ordIndex++];
        }

        @Override
        public int setDocument(int docId) {
            // For now, we consume all ords and pass them to the iter instead of doing it in a streaming way because Lucene's
            // SORTED_SET doc values are cached per thread, you can't have a fully independent instance
            values.setDocument(docId);
            int i = 0;
            for (long ord = values.nextOrd(); ord != SortedSetDocValues.NO_MORE_ORDS; ord = values.nextOrd()) {
                ords = ArrayUtil.grow(ords, i + 1);
                ords[i++] = ord;
            }
            ordIndex = 0;
            return i;
        }

        @Override
        public long currentOrd() {
            return currentOrdinal;
        }
    }

    private final static class RandomAccessSortedSetDocs extends Ordinals.AbstractDocs {

        private final RandomAccessOrds values;
        private long currentOrdinal = -1;
        private int index;

        RandomAccessSortedSetDocs(SortedSetOrdinals ordinals, RandomAccessOrds values) {
            super(ordinals);
            this.values = values;
        }

        @Override
        public long getOrd(int docId) {
            values.setDocument(docId);
            return currentOrdinal = values.nextOrd();
        }

        @Override
        public long nextOrd() {
            return currentOrdinal = values.ordAt(index++);
        }

        @Override
        public int setDocument(int docId) {
            values.setDocument(docId);
            index = 0;
            return values.cardinality();
        }

        @Override
        public long currentOrd() {
            return currentOrdinal;
        }

    }
}
