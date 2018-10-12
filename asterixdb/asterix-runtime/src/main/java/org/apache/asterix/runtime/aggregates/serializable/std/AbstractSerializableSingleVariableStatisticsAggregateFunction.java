/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.runtime.aggregates.serializable.std;

import org.apache.asterix.common.config.GlobalConfig;
import org.apache.asterix.dataflow.data.nontagged.serde.*;
import org.apache.asterix.formats.nontagged.SerializerDeserializerProvider;
import org.apache.asterix.om.base.*;
import org.apache.asterix.om.types.*;
import org.apache.asterix.om.types.hierachy.ATypeHierarchy;
import org.apache.asterix.runtime.aggregates.utils.SingleVarFunctionsUtil;
import org.apache.asterix.runtime.evaluators.common.AccessibleByteArrayEval;
import org.apache.asterix.runtime.evaluators.common.ClosedRecordConstructorEvalFactory.ClosedRecordConstructorEval;
import org.apache.asterix.runtime.exceptions.IncompatibleTypeException;
import org.apache.asterix.runtime.exceptions.UnsupportedItemTypeException;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluator;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.ISerializerDeserializer;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.data.std.api.IPointable;
import org.apache.hyracks.data.std.primitive.VoidPointable;
import org.apache.hyracks.data.std.util.ByteArrayAccessibleOutputStream;
import org.apache.hyracks.dataflow.common.data.accessors.IFrameTupleReference;
import org.apache.hyracks.api.exceptions.SourceLocation;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;

public abstract class AbstractSerializableSingleVariableStatisticsAggregateFunction
        extends AbstractSerializableAggregateFunction {

    /*
    M1, M2, M3 and M4 are the 1st to 4th central moment of a data sample
     */
    private static final int M1_FIELD_ID = 0;
    private static final int M2_FIELD_ID = 1;
    private static final int COUNT_FIELD_ID = 2;

    private static final int M1_OFFSET = 0;
    private static final int M2_OFFSET = 8;
    private static final int COUNT_OFFSET = 16;
    protected static final int AGG_TYPE_OFFSET = 24;

    private IPointable inputVal = new VoidPointable();
    private IScalarEvaluator eval;
    private AMutableDouble aDouble = new AMutableDouble(0);
    private AMutableInt64 aInt64 = new AMutableInt64(0);
    private SingleVarFunctionsUtil moments = new SingleVarFunctionsUtil();

    private IPointable resultBytes = new VoidPointable();
    private ByteArrayAccessibleOutputStream m1Bytes = new ByteArrayAccessibleOutputStream();
    private DataOutput m1BytesOutput = new DataOutputStream(m1Bytes);
    private ByteArrayAccessibleOutputStream m2Bytes = new ByteArrayAccessibleOutputStream();
    private DataOutput m2BytesOutput = new DataOutputStream(m2Bytes);
    private ByteArrayAccessibleOutputStream countBytes = new ByteArrayAccessibleOutputStream();
    private DataOutput countBytesOutput = new DataOutputStream(countBytes);
    private IScalarEvaluator evalM1 = new AccessibleByteArrayEval(m1Bytes);
    private IScalarEvaluator evalM2 = new AccessibleByteArrayEval(m2Bytes);
    private IScalarEvaluator evalCount = new AccessibleByteArrayEval(countBytes);
    private ClosedRecordConstructorEval recordEval;

    @SuppressWarnings("unchecked")
    private ISerializerDeserializer<ADouble> doubleSerde =
            SerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.ADOUBLE);
    @SuppressWarnings("unchecked")
    private ISerializerDeserializer<AInt64> longSerde =
            SerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.AINT64);
    @SuppressWarnings("unchecked")
    private ISerializerDeserializer<ANull> nullSerde =
            SerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.ANULL);

    public AbstractSerializableSingleVariableStatisticsAggregateFunction(IScalarEvaluatorFactory[] args,
            IHyracksTaskContext context, SourceLocation sourceLoc) throws HyracksDataException {
        super(sourceLoc);
        eval = args[0].createScalarEvaluator(context);
    }

    @Override
    public void init(DataOutput state) throws HyracksDataException {
        try {
            state.writeDouble(0.0);
            state.writeDouble(0.0);
            state.writeLong(0L);
            state.writeByte(ATypeTag.SERIALIZED_SYSTEM_NULL_TYPE_TAG);
            moments.set(0, 0, 0);
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public abstract void step(IFrameTupleReference tuple, byte[] state, int start, int len) throws HyracksDataException;

    @Override
    public abstract void finish(byte[] state, int start, int len, DataOutput result) throws HyracksDataException;

    @Override
    public abstract void finishPartial(byte[] state, int start, int len, DataOutput result) throws HyracksDataException;

    protected abstract void processNull(byte[] state, int start);

    protected abstract FunctionIdentifier getFunctionIdentifier();

    protected void processDataValues(IFrameTupleReference tuple, byte[] state, int start, int len)
            throws HyracksDataException {
        if (skipStep(state, start)) {
            return;
        }
        eval.evaluate(tuple, inputVal);
        byte[] bytes = inputVal.getByteArray();
        int offset = inputVal.getStartOffset();

        double m1 = BufferSerDeUtil.getDouble(state, start + M1_OFFSET);
        double m2 = BufferSerDeUtil.getDouble(state, start + M2_OFFSET);
        long count = BufferSerDeUtil.getLong(state, start + COUNT_OFFSET);
        moments.set(m1, m2, count);

        ATypeTag typeTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(bytes[offset]);
        ATypeTag aggType = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(state[start + AGG_TYPE_OFFSET]);
        if (typeTag == ATypeTag.MISSING || typeTag == ATypeTag.NULL) {
            processNull(state, start);
            return;
        } else if (aggType == ATypeTag.SYSTEM_NULL) {
            aggType = typeTag;
        } else if (typeTag != ATypeTag.SYSTEM_NULL && !ATypeHierarchy.isCompatible(typeTag, aggType)) {
            if (typeTag.ordinal() > aggType.ordinal()) {
                throw new IncompatibleTypeException(sourceLoc, getFunctionIdentifier(), bytes[offset],
                        aggType.serialize());
            } else {
                throw new IncompatibleTypeException(sourceLoc, getFunctionIdentifier(), aggType.serialize(),
                        bytes[offset]);
            }
        } else if (ATypeHierarchy.canPromote(aggType, typeTag)) {
            aggType = typeTag;
        }
        double val;
        switch (typeTag) {
            case TINYINT:
                val = AInt8SerializerDeserializer.getByte(bytes, offset + 1);
                moments.push(val);
                break;
            case SMALLINT:
                val = AInt16SerializerDeserializer.getShort(bytes, offset + 1);
                moments.push(val);
                break;
            case INTEGER:
                val = AInt32SerializerDeserializer.getInt(bytes, offset + 1);
                moments.push(val);
                break;
            case BIGINT:
                val = AInt64SerializerDeserializer.getLong(bytes, offset + 1);
                moments.push(val);
                break;
            case FLOAT:
                val = AFloatSerializerDeserializer.getFloat(bytes, offset + 1);
                moments.push(val);
                break;
            case DOUBLE:
                val = ADoubleSerializerDeserializer.getDouble(bytes, offset + 1);
                moments.push(val);
                break;
            default:
                throw new UnsupportedItemTypeException(sourceLoc, getFunctionIdentifier(), bytes[offset]);
        }
        BufferSerDeUtil.writeDouble(moments.getM1(), state, start + M1_OFFSET);
        BufferSerDeUtil.writeDouble(moments.getM2(), state, start + M2_OFFSET);
        BufferSerDeUtil.writeLong(moments.getCount(), state, start + COUNT_OFFSET);
        state[start + AGG_TYPE_OFFSET] = aggType.serialize();
    }

    protected void finishPartialResults(byte[] state, int start, int len, DataOutput result)
            throws HyracksDataException {
        double m1 = BufferSerDeUtil.getDouble(state, start + M1_OFFSET);
        double m2 = BufferSerDeUtil.getDouble(state, start + M2_OFFSET);
        long count = BufferSerDeUtil.getLong(state, start + COUNT_OFFSET);
        ATypeTag aggType = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(state[start + AGG_TYPE_OFFSET]);
        if (recordEval == null) {
            ARecordType recType = new ARecordType(null, new String[] { "m1", "m2", "count" },
                    new IAType[] { BuiltinType.ADOUBLE, BuiltinType.ADOUBLE, BuiltinType.AINT64 }, false);
            recordEval = new ClosedRecordConstructorEval(recType, new IScalarEvaluator[] { evalM1, evalM2, evalCount });
        }

        try {
            if (aggType == ATypeTag.SYSTEM_NULL) {
                if (GlobalConfig.DEBUG) {
                    GlobalConfig.ASTERIX_LOGGER.trace("Single Var statistics aggregate ran over empty input.");
                }
                result.writeByte(ATypeTag.SERIALIZED_SYSTEM_NULL_TYPE_TAG);
            } else if (aggType == ATypeTag.NULL) {
                result.writeByte(ATypeTag.SERIALIZED_NULL_TYPE_TAG);
            } else {
                m1Bytes.reset();
                aDouble.setValue(m1);
                doubleSerde.serialize(aDouble, m1BytesOutput);
                m2Bytes.reset();
                aDouble.setValue(m2);
                doubleSerde.serialize(aDouble, m2BytesOutput);
                countBytes.reset();
                aInt64.setValue(count);
                longSerde.serialize(aInt64, countBytesOutput);
                recordEval.evaluate(null, resultBytes);
                result.write(resultBytes.getByteArray(), resultBytes.getStartOffset(), resultBytes.getLength());
            }
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    protected void processPartialResults(IFrameTupleReference tuple, byte[] state, int start, int len)
            throws HyracksDataException {
        if (skipStep(state, start)) {
            return;
        }
        double m1 = BufferSerDeUtil.getDouble(state, start + M1_OFFSET);
        double m2 = BufferSerDeUtil.getDouble(state, start + M2_OFFSET);
        long count = BufferSerDeUtil.getLong(state, start + COUNT_OFFSET);
        moments.set(m1, m2, count);

        eval.evaluate(tuple, inputVal);
        byte[] serBytes = inputVal.getByteArray();
        int offset = inputVal.getStartOffset();

        ATypeTag typeTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(serBytes[offset]);
        switch (typeTag) {
            case NULL:
                processNull(state, start);
                break;
            case SYSTEM_NULL:
                // Ignore and return.
                break;
            case OBJECT:
                // Expected.
                ATypeTag aggType = ATypeTag.DOUBLE;
                int nullBitmapSize = 0;
                int offset1 = ARecordSerializerDeserializer.getFieldOffsetById(serBytes, offset, M1_FIELD_ID,
                        nullBitmapSize, false);
                int offset2 = ARecordSerializerDeserializer.getFieldOffsetById(serBytes, offset, M2_FIELD_ID,
                        nullBitmapSize, false);
                int offset3 = ARecordSerializerDeserializer.getFieldOffsetById(serBytes, offset, COUNT_FIELD_ID,
                        nullBitmapSize, false);
                double temp_m1 = ADoubleSerializerDeserializer.getDouble(serBytes, offset1);
                double temp_m2 = ADoubleSerializerDeserializer.getDouble(serBytes, offset2);
                long temp_count = AInt64SerializerDeserializer.getLong(serBytes, offset3);
                moments.combine(temp_m1, temp_m2, temp_count);

                BufferSerDeUtil.writeDouble(moments.getM1(), state, start + M1_OFFSET);
                BufferSerDeUtil.writeDouble(moments.getM2(), state, start + M2_OFFSET);
                BufferSerDeUtil.writeLong(moments.getCount(), state, start + COUNT_OFFSET);
                state[start + AGG_TYPE_OFFSET] = aggType.serialize();
                break;
            default:
                throw new UnsupportedItemTypeException(sourceLoc, getFunctionIdentifier(), serBytes[offset]);
        }
    }

    protected void finishStddevFinalResults(byte[] state, int start, int len, DataOutput result)
            throws HyracksDataException {
        double m2 = BufferSerDeUtil.getDouble(state, start + M2_OFFSET);
        long count = BufferSerDeUtil.getLong(state, start + COUNT_OFFSET);
        ATypeTag aggType = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(state[start + AGG_TYPE_OFFSET]);
        try {
            if (count <= 1 || aggType == ATypeTag.NULL) {
                nullSerde.serialize(ANull.NULL, result);
            } else {
                aDouble.setValue(Math.sqrt(m2 / (count - 1)));
                doubleSerde.serialize(aDouble, result);
            }
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    protected boolean skipStep(byte[] state, int start) {
        return false;
    }

}