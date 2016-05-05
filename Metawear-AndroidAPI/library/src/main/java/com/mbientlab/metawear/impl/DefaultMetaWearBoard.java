/*
 * Copyright 2014-2015 MbientLab Inc. All rights reserved.
 *
 * IMPORTANT: Your use of this Software is limited to those specific rights granted under the terms of a software
 * license agreement between the user who downloaded the software, his/her employer (which must be your
 * employer) and MbientLab Inc, (the "License").  You may not use this Software unless you agree to abide by the
 * terms of the License which can be found at www.mbientlab.com/terms.  The License limits your use, and you
 * acknowledge, that the Software may be modified, copied, and distributed when used in conjunction with an
 * MbientLab Inc, product.  Other than for the foregoing purpose, you may not use, reproduce, copy, prepare
 * derivative works of, modify, distribute, perform, display or sell this Software and/or its documentation for any
 * purpose.
 *
 * YOU FURTHER ACKNOWLEDGE AND AGREE THAT THE SOFTWARE AND DOCUMENTATION ARE PROVIDED "AS IS" WITHOUT WARRANTY
 * OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION, ANY WARRANTY OF MERCHANTABILITY, TITLE,
 * NON-INFRINGEMENT AND FITNESS FOR A PARTICULAR PURPOSE. IN NO EVENT SHALL MBIENTLAB OR ITS LICENSORS BE LIABLE OR
 * OBLIGATED UNDER CONTRACT, NEGLIGENCE, STRICT LIABILITY, CONTRIBUTION, BREACH OF WARRANTY, OR OTHER LEGAL EQUITABLE
 * THEORY ANY DIRECT OR INDIRECT DAMAGES OR EXPENSES INCLUDING BUT NOT LIMITED TO ANY INCIDENTAL, SPECIAL, INDIRECT,
 * PUNITIVE OR CONSEQUENTIAL DAMAGES, LOST PROFITS OR LOST DATA, COST OF PROCUREMENT OF SUBSTITUTE GOODS, TECHNOLOGY,
 * SERVICES, OR ANY CLAIMS BY THIRD PARTIES (INCLUDING BUT NOT LIMITED TO ANY DEFENSE THEREOF), OR OTHER SIMILAR COSTS.
 *
 * Should you have any questions regarding your right to use this Software, contact MbientLab via email:
 * hello@mbientlab.com.
 */

package com.mbientlab.metawear.impl;

import android.util.Log;

import com.mbientlab.metawear.AsyncOperation;
import com.mbientlab.metawear.data.Bmp280AltitudeMessage;
import com.mbientlab.metawear.DataSignal;
import com.mbientlab.metawear.Message;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.RouteManager;
import com.mbientlab.metawear.UnsupportedModuleException;
import com.mbientlab.metawear.impl.characteristic.*;
import com.mbientlab.metawear.module.*;
import com.mbientlab.metawear.processor.*;
import com.mbientlab.metawear.data.*;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.lang.Math;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by etsai on 6/15/2015.
 */
public abstract class DefaultMetaWearBoard implements MetaWearBoard {
    private static Collection<Byte> JsonByteArrayToCollection(JSONArray jsonArray) throws JSONException {
        Collection<Byte> elements= new ArrayList<>(jsonArray.length());
        for(int i= 0; i < jsonArray.length(); i++) {
            elements.add((byte) jsonArray.getInt(i));
        }

        return elements;
    }

    private static String arrayToHexString(byte[] value) {
        if (value.length == 0) {
            return "[]";
        }

        StringBuilder builder= new StringBuilder();
        builder.append(String.format("[0x%02x", value[0]));
        for(int i= 1; i < value.length; i++) {
            builder.append(String.format(", 0x%02x", value[i]));
        }
        builder.append("]");

        return builder.toString();
    }

    private static Map<String, String> parseQuery(String query) {
        HashMap<String, String> queryTokens= new HashMap<>();

        for(String token: query.split("&")) {
            String[] keyVal= token.split("=");
            queryTokens.put(keyVal[0], keyVal[1]);
        }

        return queryTokens;
    }

    private static int closestIndex(float[] values, float key) {
        float boundedKey= Math.min(Math.max(values[0], key), values[values.length - 1]);
        byte i;
        for(i= 0; i < values.length - 1; i++) {
            if (values[i] <= boundedKey && values[i + 1] >= boundedKey) {
                break;
            }
        }

        if (i == values.length - 1) {
            return values.length - 1;
        }

        float leftDist= boundedKey - values[i], rightDist= values[i + 1] - boundedKey;
        if (leftDist < rightDist) {
            return i;
        } else {
            return i + 1;
        }
    }

    private static String createUnsupportedModuleMsg(Class<?> moduleClass, String modelNumber) {
        return String.format("Module \'%s\' not supported for this board (module= %s)", moduleClass.getSimpleName(), modelNumber);
    }
    private static String createUnsupportedModuleMsg(Class<?> moduleClass) {
        return String.format("Module \'%s\' not supported for this firmware version", moduleClass.getSimpleName());
    }
    private static String createMinVersionMsg(Class<?> moduleClass, Version minVersion) {
        return String.format("Module \'%s\' requires min firmware version \'%s\'", moduleClass.getSimpleName(), minVersion.toString());
    }

    public final static byte MW_COMMAND_LENGTH = 18;

    private static final long CREATOR_RESPONSE_TIME= 1500L, EVENT_COMMAND_TIME= 5500L;
    private static final byte X_OFFSET= 0, Y_OFFSET= 2, Z_OFFSET= 4;
    private static final String JSON_FIELD_MSG_CLASS= "message_class", JSON_FIELD_LOG= "logs", JSON_FIELD_ROUTE= "route",
            JSON_FIELD_SUBSCRIBERS="subscribers", JSON_FIELD_NAME= "name", JSON_FIELD_TIMER= "timers", JSON_FIELD_CMD_IDS="cmd_ids",
            JSON_FIELD_MMA8452Q_ODR= "mma8452q_odr", JSON_FIELD_BMI160_ACC_RANGE= "bmi160_acc_range", JSON_FIELD_BMI160_GRYO_RANGE= "bmi160_gyro_range",
            JSON_FIELD_ROUTE_MANAGER= "route_managers", JSON_FIELD_LOG_REFERENCE_TICKS= "log_reference_ticks";
    private final static double TICK_TIME_STEP= (48.0 / 32768.0) * 1000.0;
    private static final HashMap<String, Class<? extends DataSignal.ProcessorConfig>> processorSchemes;
    private static final HashMap<Class<? extends Message>, Class<? extends Message>> unsignedToSigned, signedToUnsigned, unpackedToRms;
    private static final HashSet<Class<? extends Message>> bmi160GyroMessageClasses, bmi160AccMessageClasses, signedMsgClasses;

    static {
        processorSchemes= new HashMap<>();
        processorSchemes.put(Accumulator.SCHEME_NAME, Accumulator.class);
        processorSchemes.put(Average.SCHEME_NAME, Average.class);
        processorSchemes.put(Delta.SCHEME_NAME, Delta.class);
        processorSchemes.put(Maths.SCHEME_NAME, Maths.class);
        processorSchemes.put(Rms.SCHEME_NAME, Rms.class);
        processorSchemes.put(Rss.SCHEME_NAME, Rss.class);
        processorSchemes.put(Time.SCHEME_NAME, Time.class);
        processorSchemes.put(Threshold.SCHEME_NAME, Threshold.class);
        processorSchemes.put(Comparison.SCHEME_NAME, Comparison.class);
        processorSchemes.put(Passthrough.SCHEME_NAME, Passthrough.class);
        processorSchemes.put(Sample.SCHEME_NAME, Sample.class);
        processorSchemes.put(Counter.SCHEME_NAME, Counter.class);
        processorSchemes.put(Pulse.SCHEME_NAME, Pulse.class);
        processorSchemes.put(Buffer.SCHEME_NAME, Buffer.class);

        unsignedToSigned= new HashMap<>();
        unsignedToSigned.put(UnsignedMessage.class, SignedMessage.class);
        unsignedToSigned.put(Bmi160SingleAxisUnsignedMessage.class, Bmi160SingleAxisMessage.class);
        unsignedToSigned.put(Bmi160SingleAxisUnsignedGyroMessage.class, Bmi160SingleAxisGyroMessage.class);
        unsignedToSigned.put(Mma8452qSingleAxisUnsignedMessage.class, Mma8452qSingleAxisMessage.class);
        unsignedToSigned.put(Bmm150SingleAxisUnsignedMessage.class, Bmm150SingleAxisMessage.class);

        signedMsgClasses= new HashSet<>();
        signedMsgClasses.add(TemperatureMessage.class);
        signedMsgClasses.add(Bmp280PressureMessage.class);
        signedMsgClasses.add(Bmp280AltitudeMessage.class);

        signedToUnsigned= new HashMap<>();
        signedToUnsigned.put(SignedMessage.class, UnsignedMessage.class);
        signedToUnsigned.put(Bmi160SingleAxisMessage.class, Bmi160SingleAxisUnsignedMessage.class);
        signedToUnsigned.put(Bmi160SingleAxisGyroMessage.class, Bmi160SingleAxisUnsignedGyroMessage.class);
        signedToUnsigned.put(Mma8452qSingleAxisMessage.class, Mma8452qSingleAxisUnsignedMessage.class);
        signedToUnsigned.put(Bmm150SingleAxisMessage.class, Bmm150SingleAxisUnsignedMessage.class);

        bmi160AccMessageClasses= new HashSet<>();
        bmi160AccMessageClasses.add(Bmi160SingleAxisUnsignedMessage.class);
        bmi160AccMessageClasses.add(Bmi160SingleAxisMessage.class);
        bmi160AccMessageClasses.add(Bmi160ThreeAxisMessage.class);

        bmi160GyroMessageClasses= new HashSet<>();
        bmi160GyroMessageClasses.add(Bmi160SingleAxisUnsignedGyroMessage.class);
        bmi160GyroMessageClasses.add(Bmi160SingleAxisGyroMessage.class);
        bmi160GyroMessageClasses.add(Bmi160ThreeAxisGyroMessage.class);

        unpackedToRms = new HashMap<>();
        unpackedToRms.put(Bmi160ThreeAxisMessage.class, Bmi160SingleAxisUnsignedMessage.class);
        unpackedToRms.put(Bmi160ThreeAxisGyroMessage.class, Bmi160SingleAxisUnsignedGyroMessage.class);
        unpackedToRms.put(Mma8452qThreeAxisMessage.class, Mma8452qSingleAxisUnsignedMessage.class);
        unpackedToRms.put(Bmm150ThreeAxisMessage.class, Bmm150SingleAxisUnsignedMessage.class);
    }

    private interface IdCreator {
        void execute();
        void receivedId(byte id);
    }

    private interface EventListener {
        void queueCommand(byte[] command);
        void receivedCommandId(byte id);
        void onCommandWrite();
    }

    private class ReferenceTick {
        private final byte resetUid;
        private final long tick;
        private final Calendar timestamp;

        public ReferenceTick(JSONObject state) throws JSONException {
            resetUid= (byte) state.getInt("reset_uid");
            tick= state.getInt("tick_count") & 0xffffffffL;

            long high= state.getInt("timestamp_high") & 0xffffffffL, low= state.getInt("timestamp_low") & 0xffffffffL;
            long epocOffset= (high << 32) | low;
            timestamp= Calendar.getInstance();
            timestamp.setTimeInMillis(epocOffset);
        }

        public ReferenceTick(byte resetUid, long tick, Calendar timestamp) {
            this.timestamp= timestamp;
            this.tick= tick;
            this.resetUid= resetUid;
        }

        public byte resetUid() {
            return resetUid;
        }

        public long tickCount() {
            return tick;
        }

        public Calendar timestamp() {
            return timestamp;
        }

        public JSONObject serialize() throws JSONException {
            JSONObject state= new JSONObject();

            state.put("reset_uid", resetUid);
            state.put("tick_count", (int) tick);

            long epocOffset= timestamp.getTimeInMillis();
            state.put("timestamp_high", epocOffset >> 32);
            state.put("timestamp_low", epocOffset);

            return state;
        }
    }

    private interface RouteHandler {
        void begin();
        void receivedId(byte id);
    }

    private class RouteManagerImpl implements RouteManager {
        private static final String JSON_FIELD_ID= "id", JSON_FIELD_FILTER_IDS="filter_ids", JSON_FIELD_ROUTE_SUB_KEYS= "sub_keys",
                JSON_FIELD_ROUTE_LOG_KEYS= "log_keys";

        private int routeId= -1;
        private final HashSet<Byte> filterIds= new HashSet<>();
        private final HashSet<String> subscriptionKeys = new HashSet<>(), loggerKeys= new HashSet<>(), routeDpKeys = new HashSet<>();
        private final HashSet<Byte> routeEventCmdIds = new HashSet<>();
        private boolean active= true;

        public RouteManagerImpl() { }

        public RouteManagerImpl(JSONObject state) throws JSONException {
            routeId= state.getInt(JSON_FIELD_ID);

            JSONArray loggerKeysState= state.getJSONArray(JSON_FIELD_ROUTE_LOG_KEYS);
            for(byte i= 0; i < loggerKeysState.length(); i++) {
                loggerKeys.add(loggerKeysState.getString(i));
            }

            JSONArray subKeysState= state.getJSONArray(JSON_FIELD_ROUTE_SUB_KEYS);
            for(byte i= 0; i < subKeysState.length(); i++) {
                subscriptionKeys.add(subKeysState.getString(i));
            }

            JSONArray eventCmdIdsState= state.getJSONArray(JSON_FIELD_CMD_IDS);
            for(byte i= 0; i < eventCmdIdsState.length(); i++) {
                routeEventCmdIds.add((byte) eventCmdIdsState.getInt(i));
            }

            JSONArray filterIdsState= state.getJSONArray(JSON_FIELD_FILTER_IDS);
            for(byte i= 0; i < filterIdsState.length(); i++) {
                filterIds.add((byte) filterIdsState.getInt(i));
            }
        }

        public JSONObject serializeState() throws JSONException {
            return new JSONObject().put(JSON_FIELD_ROUTE_LOG_KEYS, new JSONArray(loggerKeys)).put(JSON_FIELD_ROUTE_SUB_KEYS, new JSONArray(subscriptionKeys))
                    .put(JSON_FIELD_CMD_IDS, new JSONArray(routeEventCmdIds)).put(JSON_FIELD_ID, routeId).put(JSON_FIELD_FILTER_IDS, new JSONArray(filterIds));
        }

        public void setRouteId(int id) {
            routeId= id;
            dataRoutes.put(routeId, this);
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public Collection<String> getStreamKeys() {
            return Collections.unmodifiableSet(subscriptionKeys);
        }

        @Override
        public int id() {
            return routeId;
        }

        @Override
        public void remove() {
            if (!active) {
                return;
            }

            active= false;
            for(String key: subscriptionKeys) {
                dataSubscriptions.get(key).unsubscribe();
                dataSubscriptions.remove(key);
            }
            subscriptionKeys.clear();

            for(String key: loggerKeys) {
                dataLoggerKeys.get(key).remove();
                dataLoggerKeys.remove(key);
            }
            loggerKeys.clear();

            for(Byte id: filterIds) {
                writeRegister(DataProcessorRegister.REMOVE, id);
                dataProcMsgClasses.remove(new ResponseHeader(DataProcessorRegister.NOTIFY, id));
            }
            filterIds.clear();

            for(Byte id: routeEventCmdIds) {
                writeRegister(EventRegister.REMOVE, id);
            }
            routeEventCmdIds.clear();

            for(String key: routeDpKeys) {
                dataProcessors.remove(key);
            }
            routeDpKeys.clear();

            dataRoutes.remove(routeId);
        }

        @Override
        public boolean unsubscribe(String streamKey) {
            if (active && subscriptionKeys.contains(streamKey)) {
                dataSubscriptions.get(streamKey).unsubscribe();
                return true;
            }
            return false;
        }

        @Override
        public boolean subscribe(String streamKey, MessageHandler processor) {
            if (active) {
                if (!subscriptionKeys.contains(streamKey) && !dataSubscriptions.containsKey(streamKey) || !dataRoutes.containsKey(routeId)) {
                    return false;
                }

                dataSubscriptions.get(streamKey).resubscribe(processor);
                return true;
            }
            return false;
        }

        @Override
        public Collection<String> getLogKeys() {
            return Collections.unmodifiableSet(loggerKeys);
        }

        @Override
        public boolean setLogMessageHandler(String logKey, MessageHandler processor) {
            if (active) {
                if (!loggerKeys.contains(logKey) && !dataLoggerKeys.containsKey(logKey) || !dataRoutes.containsKey(routeId)) {
                    return false;
                }

                dataLoggerKeys.get(logKey).attach(processor);
                return true;
            }
            return false;
        }
    }

    private interface Subscription {
        String JSON_FIELD_NOTIFY_BYTES= "notify", JSON_FIELD_SILENCE_BYTES= "silence", JSON_FIELD_HEADER= "header";

        void unsubscribe();
        void resubscribe(RouteManager.MessageHandler processor);
        JSONObject serializeState();
    }
    private class ProcessorSubscription extends SensorSubscription {
        public ProcessorSubscription(JSONObject state) throws JSONException {
            super(state);
        }

        @Override
        public void unsubscribe() {
            nProcSubscribers--;
            if (nProcSubscribers == 0) {
                writeRegister(DataProcessorRegister.NOTIFY, (byte) 0x0);
            }

            super.unsubscribe();
        }

        @Override
        public void resubscribe(RouteManager.MessageHandler processor) {
            writeRegister(DataProcessorRegister.NOTIFY, (byte) 0x1);
            nProcSubscribers++;
            super.resubscribe(processor);
        }
    }
    private class SensorSubscription implements Subscription {
        private final ResponseHeader header;
        private final byte[] notifyBytes, silenceBytes;
        private final JSONObject state;

        public SensorSubscription(JSONObject state) throws JSONException {
            this.state= state;

            JSONArray headerState= state.getJSONArray(JSON_FIELD_HEADER);
            header= new ResponseHeader((byte) headerState.getInt(0), (byte) headerState.getInt(1), (byte) headerState.getInt(2));

            JSONArray silenceState= state.getJSONArray(JSON_FIELD_SILENCE_BYTES);
            silenceBytes= new byte[silenceState.length()];
            for(byte i= 0; i < silenceState.length(); i++) {
                silenceBytes[i]= (byte) silenceState.getInt(i);
            }

            JSONArray notifyState= state.getJSONArray(JSON_FIELD_NOTIFY_BYTES);
            notifyBytes= new byte[notifyState.length()];
            for(byte i= 0; i < notifyState.length(); i++) {
                notifyBytes[i]= (byte) notifyState.getInt(i);
            }
        }
        @Override
        public void unsubscribe() {
            conn.sendCommand(false, silenceBytes);
            responseProcessors.remove(header);
        }

        @Override
        public void resubscribe(RouteManager.MessageHandler processor) {
            conn.sendCommand(false, notifyBytes);
            responseProcessors.put(header, processor);
        }

        @Override
        public JSONObject serializeState() {
            return state;
        }
    }

    private abstract class Loggable {
        public static final String JSON_FIELD_LOGGER_IDS = "ids", JSON_FIELD_HANDLER_CLASS= "handler_class", JSON_FIELD_LOG_KEY= "key";

        private final Class<? extends Message> msgClass;
        private RouteManager.MessageHandler logProcessor;
        private final String key;

        public Loggable(String key, Class<? extends Message> msgClass) {
            this.msgClass= msgClass;
            this.key= key;
        }

        public void processLogMessage(byte logId, Calendar timestamp, byte[] data) {
            try {
                final Message logMsg;

                if (bmi160AccMessageClasses.contains(msgClass)) {
                    Constructor<?> cTor = msgClass.getConstructor(Calendar.class, byte[].class, float.class);
                    logMsg= (Message) cTor.newInstance(timestamp, data, bmi160AccRange.scale());
                } else if (bmi160GyroMessageClasses.contains(msgClass)) {
                    Constructor<?> cTor = msgClass.getConstructor(Calendar.class, byte[].class, float.class);
                    logMsg= (Message) cTor.newInstance(timestamp, data, bmi160GyroRange.scale());
                } else {
                    Constructor<?> cTor= msgClass.getConstructor(Calendar.class, byte[].class);
                    logMsg= (Message) cTor.newInstance(timestamp, data);
                }

                conn.executeTask(new Runnable() {
                    @Override
                    public void run() {
                        if (logProcessor != null) {
                            logProcessor.process(logMsg);
                        } else if (downloadHandler != null) {
                            downloadHandler.receivedUnhandledLogEntry(logMsg);
                        }
                    }
                });
            } catch (Exception ex) {
                throw new RuntimeException("Cannot instantiate message class", ex);
            }
        }

        public JSONObject serializeState() throws JSONException {
            return new JSONObject().put(JSON_FIELD_LOG_KEY, key)
                    .put(JSON_FIELD_MSG_CLASS, msgClass.getName())
                    .put(JSON_FIELD_HANDLER_CLASS, this.getClass().getName());

        }

        public void attach(RouteManager.MessageHandler processor) {
            logProcessor= processor;
        }

        public String getKey() {
            return key;
        }

        public abstract void remove();
    }
    /**
     * Legacy class left over from 2.0.0 implementation.  All future loggers should use the VariableLoggable class
     */
    private class StandardLoggable extends VariableLoggable {
        public StandardLoggable(String key, Collection<Byte> logIds, Class<? extends Message> msgClass) {
            super(key, logIds, msgClass, (byte) 1);
        }

        public StandardLoggable(JSONObject state) throws JSONException, ClassNotFoundException {
            this(state.getString(JSON_FIELD_LOG_KEY), JsonByteArrayToCollection(state.getJSONArray(JSON_FIELD_LOGGER_IDS)),
                    (Class<Message>) Class.forName(state.getString(JSON_FIELD_MSG_CLASS)));
        }
    }
    /**
     * Legacy class left over from 2.0.0 implementation.  All future loggers should use the VariableLogger class
     */
    private class ThreeAxisLoggable extends VariableLoggable {
        public ThreeAxisLoggable(String key, Collection<Byte> logIds, Class<? extends Message> msgClass) {
            super(key, logIds, msgClass, (byte) 6);
        }

        public ThreeAxisLoggable(JSONObject state) throws JSONException, ClassNotFoundException {
            this(state.getString(JSON_FIELD_LOG_KEY), JsonByteArrayToCollection(state.getJSONArray(JSON_FIELD_LOGGER_IDS)),
                    (Class<Message>) Class.forName(state.getString(JSON_FIELD_MSG_CLASS)));
        }
    }
    private class VariableLoggable extends Loggable {
        private static final String JSON_FIELD_EXPECTED_SIZE= "expected_size";

        ///< Use linked hash map to preserve the order in which log IDs are received from the board
        private final LinkedHashMap<Byte, Queue<byte[]>> logEntries;
        private final int expectedSize;

        public VariableLoggable(String key, Collection<Byte> logIds, Class<? extends Message> msgClass, int expectedSize) {
            super(key, msgClass);

            this.expectedSize= expectedSize;

            logEntries= new LinkedHashMap<>();
            for(Byte id: logIds) {
                dataLoggers.put(new ResponseHeader(LoggingRegister.READOUT_NOTIFY, id), this);
                logEntries.put(id, new ConcurrentLinkedQueue<byte[]>());
            }
        }

        public VariableLoggable(JSONObject state) throws JSONException, ClassNotFoundException {
            this(state.getString(JSON_FIELD_LOG_KEY), JsonByteArrayToCollection(state.getJSONArray(JSON_FIELD_LOGGER_IDS)),
                    (Class<Message>) Class.forName(state.getString(JSON_FIELD_MSG_CLASS)),
                    state.getInt(JSON_FIELD_EXPECTED_SIZE));
        }

        @Override
        public void processLogMessage(byte logId, Calendar timestamp, byte[] data) {
            if (logEntries.containsKey(logId)) {
                logEntries.get(logId).add(data);
            } else {
                throw new RuntimeException("Unknown log id in this fn: " + logId);
            }

            boolean noneEmpty= true;
            for(Queue<byte[]> cachedValues: logEntries.values()) {
                noneEmpty&= !cachedValues.isEmpty();
            }

            if (noneEmpty) {
                ArrayList<byte[]> entries= new ArrayList<>(logEntries.values().size());
                for(Queue<byte[]> cachedValues: logEntries.values()) {
                    entries.add(cachedValues.poll());
                }

                byte[] merged= new byte[expectedSize];
                int offset= 0;
                for(int i= 0; i < entries.size(); i++) {
                    int copyLength= Math.min(entries.get(i).length, expectedSize - offset);
                    System.arraycopy(entries.get(i), 0, merged, offset, copyLength);
                    offset+= entries.get(i).length;
                }

                super.processLogMessage(logId, timestamp, merged);
            }
        }

        @Override
        public JSONObject serializeState() throws JSONException {
            JSONArray loggerIds= new JSONArray();
            for(Byte id: logEntries.keySet()) {
                loggerIds.put(id);
            }

            return super.serializeState().put(JSON_FIELD_LOGGER_IDS, loggerIds).put(JSON_FIELD_EXPECTED_SIZE, expectedSize);
        }

        @Override
        public void remove() {
            for(Byte id: logEntries.keySet()) {
                dataLoggers.remove(new ResponseHeader(LoggingRegister.READOUT_NOTIFY, id));
            }
        }
    }

    private enum ProcessorType {
        ACCUMULATOR,
        AVERAGE,
        BUFFER,
        COMPARISON,
        COUNTER,
        DELTA,
        MATHS,
        PASSTHROUGH,
        PULSE,
        RMS,
        RSS,
        SAMPLE,
        THRESHOLD,
        TIME
    }
    private enum ReadType {
        NONE,
        NORMAL {
            @Override public byte mask() {
                return (byte) 0x80;
            }
        },
        SILENT {
            @Override public byte mask() {
                return (byte) 0xc0;
            }
        };

        public byte mask() {
            return 0;
        }
    }
    private class RouteBuilder implements EventListener, RouteHandler {
        private AsyncOperation<RouteManager> commitResult;
        private byte nExpectedCmds= 0;

        private final RouteManagerImpl manager= new RouteManagerImpl();
        private final Stack<DataSignalImpl> branches= new Stack<>();
        private final Queue<IdCreator> creators= new LinkedList<>();
        private final Map<DataSignalImpl, DataSignal.ActivityHandler> signalMonitors= new LinkedHashMap<>();
        private final Queue<byte[]> tapCommands= new LinkedList<>();

        private class InvalidDataSignal implements DataSignal {
            private final Throwable error;

            public InvalidDataSignal(Throwable error) {
                this.error= error;
            }

            @Override
            public DataSignal split() { return this; }

            @Override
            public DataSignal branch() { return this; }

            @Override
            public DataSignal end() { return this; }

            @Override
            public DataSignal log(String key) { return this; }

            @Override
            public DataSignal stream(String key) { return this; }

            @Override
            public DataSignal monitor(ActivityHandler handler) { return this; }

            @Override
            public DataSignal process(String key, ProcessorConfig config) { return this; }

            @Override
            public DataSignal process(ProcessorConfig config) { return this; }

            @Override
            public DataSignal process(String configUri) { return this; }

            @Override
            public DataSignal process(String key, String configUri) { return this; }

            @Override
            public AsyncOperation<RouteManager> commit() {
                commitResult= conn.createAsyncOperation();
                manager.remove();
                conn.setResultReady(commitResult, null, error);
                return commitResult;
            }
        }
        private abstract class DataSignalImpl implements DataSignal, DataSignal.DataToken, Subscription {
            protected DataSignalImpl parent;
            protected Class<? extends Message> msgClass;
            protected final byte outputSize, channelSize, nChannels;

            protected DataSignalImpl(byte channelSize, byte nChannels, Class<? extends Message> msgClass) {
                this.outputSize= (byte) (channelSize * nChannels);
                this.msgClass= msgClass;
                this.channelSize= channelSize;
                this.nChannels= nChannels;
            }

            @Override
            public DataSignal split() {
                branches.push(this);
                return this;
            }

            @Override
            public DataSignal branch() {
                return branches.isEmpty() ? null : branches.peek();
            }

            @Override
            public DataSignal end() {
                branches.pop();
                final Throwable error= new UnsupportedOperationException("Route has ended, can only commit");
                return branches.isEmpty() ? new DataSignal() {
                    @Override
                    public DataSignal split() {
                        return new InvalidDataSignal(error);
                    }

                    @Override
                    public DataSignal branch() {
                        return new InvalidDataSignal(error);
                    }

                    @Override
                    public DataSignal end() {
                        return new InvalidDataSignal(error);
                    }

                    @Override
                    public DataSignal log(String key) {
                        return new InvalidDataSignal(error);
                    }

                    @Override
                    public DataSignal stream(String key) {
                        return new InvalidDataSignal(error);
                    }

                    @Override
                    public DataSignal monitor(ActivityHandler handler) {
                        return new InvalidDataSignal(error);
                    }

                    @Override
                    public DataSignal process(String key, ProcessorConfig config) {
                        return new InvalidDataSignal(error);
                    }

                    @Override
                    public DataSignal process(ProcessorConfig config) {
                        return new InvalidDataSignal(error);
                    }

                    @Override
                    public DataSignal process(String configUri) {
                        return new InvalidDataSignal(error);
                    }

                    @Override
                    public DataSignal process(String key, String configUri) {
                        return new InvalidDataSignal(error);
                    }

                    @Override
                    public AsyncOperation<RouteManager> commit() {
                        return DataSignalImpl.this.commit();
                    }
                } : branches.peek();
            }

            @Override
            public DataSignal process(String key, ProcessorConfig config) {
                if (dataProcessors.containsKey(key)) {
                    return new InvalidDataSignal(new RuntimeException("Processor configuration key \'" + key + "\' already present"));
                }

                ProcessedDataSignal newDataSignal= (ProcessedDataSignal) process(config, this);
                manager.routeDpKeys.add(key);
                dataProcessors.put(key, newDataSignal);
                return newDataSignal;
            }

            @Override
            public DataSignal process(ProcessorConfig config) {
                return process(config, this);
            }

            @Override
            public DataSignal process(String configUri) {
                String[] uriSplit= configUri.split("\\?");

                if (processorSchemes.containsKey(uriSplit[0])) {
                    try {
                        Map<String, String> query= uriSplit.length > 1 ? parseQuery(uriSplit[1]) : new HashMap<String, String>();
                        Constructor<?> cTor = processorSchemes.get(uriSplit[0]).getConstructor(Map.class);
                        return process((ProcessorConfig) cTor.newInstance(query));
                    } catch (Exception e) {
                        return new InvalidDataSignal(new RuntimeException("Error instantiating data filter \'" + uriSplit[0] + "\'", e));
                    }
                } else {
                    return new InvalidDataSignal(new RuntimeException("Processor configuration scheme \'" + uriSplit[0] + "\' not recognized"));
                }
            }

            @Override
            public DataSignal process(String key, String configUri) {
                ProcessedDataSignal newDataSignal= (ProcessedDataSignal) process(configUri);
                if (dataProcessors.containsKey(key)) {
                    return new InvalidDataSignal(new RuntimeException("Processor configuration key \'" + key + "\' already present"));
                }
                manager.routeDpKeys.add(key);
                dataProcessors.put(key, newDataSignal);
                return newDataSignal;
            }

            protected void postFilterCreate(final ProcessedDataSignal newProcessor, final DataSignalImpl parent) {
                newProcessor.parent= parent;
                creators.add(new IdCreator() {
                    @Override
                    public void execute() {
                        byte[] nextCfg = newProcessor.getFilterConfig();
                        byte[] parentCfg= parent.getTriggerConfig();
                        byte[] addFilter = new byte[parentCfg.length + nextCfg.length];
                        System.arraycopy(parentCfg, 0, addFilter, 0, parentCfg.length);
                        System.arraycopy(nextCfg, 0, addFilter, parentCfg.length, nextCfg.length);
                        writeRegister(DataProcessorRegister.ADD, addFilter);
                    }

                    @Override
                    public void receivedId(byte id) {
                        manager.filterIds.add(id);
                        newProcessor.setId(id);
                    }
                });
            }

            @Override
            public DataSignal log(String key) {
                return log(key, this);
            }

            private final int LOG_ENTRY_SIZE= 4;
            private final ArrayList<Byte> logIds= new ArrayList<>();

            protected final DataSignal log(final String key, final DataSignalImpl source) {
                if (dataLoggerKeys.containsKey(key)) {
                    return new InvalidDataSignal(new RuntimeException("Duplicate logging keys found: " + key));
                }

                ///< outputSize must be a positive number
                final int nReqLogIds= (outputSize - 1) / LOG_ENTRY_SIZE + 1;
                int remainder= outputSize;

                for(int i= 0; i < nReqLogIds; i++, remainder-= LOG_ENTRY_SIZE) {
                    final int entrySize= Math.min(remainder, LOG_ENTRY_SIZE), entryOffset= LOG_ENTRY_SIZE * i + offset();
                    creators.add(new IdCreator() {
                        @Override
                        public void execute() {
                            final byte[] eventConfig = source.getEventConfig();
                            final byte[] xyLogCfg = new byte[eventConfig.length + 1];
                            System.arraycopy(eventConfig, 0, xyLogCfg, 0, eventConfig.length);
                            xyLogCfg[eventConfig.length] = (byte) (((entrySize - 1) << 5) | entryOffset);

                            writeRegister(LoggingRegister.TRIGGER, xyLogCfg);
                        }

                        @Override
                        public void receivedId(byte id) {
                            logIds.add(id);

                            if (logIds.size() >= nReqLogIds) {
                                Loggable newLogger= new VariableLoggable(key, logIds, msgClass, outputSize);
                                dataLoggerKeys.put(key, newLogger);
                                manager.loggerKeys.add(key);
                            }
                        }
                    });
                }
                return this;
            }

            @Override
            public DataSignal stream(String key) {
                dataSubscriptions.put(key, this);
                manager.subscriptionKeys.add(key);
                return this;
            }

            @Override
            public DataSignal monitor(ActivityHandler handler) {
                signalMonitors.put(this, handler);
                return this;
            }

            @Override
            public AsyncOperation<RouteManager> commit() {
                commitResult= conn.createAsyncOperation();

                if (isConnected()) {
                    pendingRoutes.add(RouteBuilder.this);
                    if (!commitRoutes.get()) {
                        commitRoutes.set(true);
                        pendingRoutes.peek().begin();
                    }
                } else {
                    conn.setResultReady(commitResult, null, new RuntimeException("Not connected to a MetaWear board"));
                }

                return commitResult;
            }

            @Override
            public byte length() {
                return outputSize;
            }

            @Override
            public byte offset() {
                return 0;
            }

            protected DataSignal process(ProcessorConfig config, final DataSignalImpl parent) {
                if (outputSize > 4) {
                    if (config instanceof Comparison) {
                        return new InvalidDataSignal(new UnsupportedOperationException("Cannot compare data longer than 4 bytes"));
                    } else if (config instanceof Sample) {
                        return new InvalidDataSignal(new UnsupportedOperationException("Cannot attach sample filter to data longer than 4 bytes"));
                    } else if (config instanceof Accumulator) {
                        return new InvalidDataSignal(new UnsupportedOperationException("Cannot accumulate data longer than 4 bytes"));
                    } else if (config instanceof Average) {
                        return new InvalidDataSignal(new UnsupportedOperationException("Cannot average data longer than 4 bytes"));
                    } else if (config instanceof Maths && firmwareVersion.compareTo(Constant.MULTI_CHANNEL_MATH) < 0) {
                        return new InvalidDataSignal(new UnsupportedOperationException("Cannot perform arithmetic operations on data longer than 4 bytes"));
                    } else if (config instanceof Delta) {
                        return new InvalidDataSignal(new UnsupportedOperationException("Cannot attach delta transformer to data longer than 4 bytes"));
                    } else if (config instanceof Pulse) {
                        return new InvalidDataSignal(new UnsupportedOperationException("Cannot detect pulses on data longer than 4 bytes"));
                    } else if (config instanceof Threshold) {
                        return new InvalidDataSignal(new UnsupportedOperationException("Cannot detect thresholds on data longer than 4 bytes"));
                    }
                }

                ProcessedDataSignal newProcessor;
                if (config instanceof Passthrough) {
                    final Passthrough params= (Passthrough) config;
                    newProcessor= new StaticProcessedDataSignal(channelSize, nChannels, msgClass) {
                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(params);
                        }

                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newConfig) {
                            Passthrough passthroughConfig= (Passthrough) newConfig;
                            ByteBuffer buffer= ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).put((byte) 0x1)
                                    .put((byte) (passthroughConfig.passthroughMode.ordinal() & 0x7))
                                    .putShort(passthroughConfig.value);
                            return buffer.array();
                        }

                        @Override
                        public void setState(State newState) {
                            if (!(newState instanceof Passthrough.State)) {
                                throw new ClassCastException("Passthrough state can only be modified with a Passthrough.State object");
                            }
                            super.setState(newState);
                        }

                        @Override
                        public ProcessorType getType() {
                            return ProcessorType.PASSTHROUGH;
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            if (!(newConfig instanceof Passthrough)) {
                                throw new ClassCastException("Can only swap the current configuration with another passthrough configuration");
                            }

                            super.modifyConfiguration(newConfig);
                        }
                    };
                } else if (config instanceof Comparison) {
                    final Comparison params= (Comparison) config;
                    newProcessor= new StaticProcessedDataSignal(channelSize, nChannels, msgClass) {
                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(params);
                        }

                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newFilter) {
                            Comparison comparisonConfig= (Comparison) newFilter;
                            boolean signed= comparisonConfig.signed == null ? isSigned() : comparisonConfig.signed;
                            Number firmwareReference= numberToFirmwareUnits(comparisonConfig.reference);
                            ByteBuffer buffer= ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put((byte) 0x6)
                                    .put((byte) (signed ? 1 : 0)).put((byte) comparisonConfig.compareOp.ordinal()).put((byte) 0)
                                    .putInt(firmwareReference.intValue());
                            return buffer.array();
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            if (!(newConfig instanceof Comparison)) {
                                throw new ClassCastException("Can only swap the current configuration with another comparison configuration");
                            }

                            Comparison newCompFilter= (Comparison) newConfig;

                            if (newCompFilter.referenceToken != null) {
                                eventParams= newCompFilter.referenceToken;
                                eventDestOffset= 5;
                            }
                            super.modifyConfiguration(newConfig);
                            eventParams= null;
                        }

                        @Override
                        public void setState(State newState) {
                            throw new UnsupportedOperationException("Cannot change comparison filter state");
                        }

                        @Override
                        public ProcessorType getType() {
                            return ProcessorType.COMPARISON;
                        }
                    };
                } else if (config instanceof Time) {
                    final Time params = (Time) config;

                    Class<? extends Message> nextMsgClass= msgClass;
                    if (params.mode == Time.OutputMode.DIFFERENTIAL && unsignedToSigned.containsKey(msgClass)) {
                        nextMsgClass= unsignedToSigned.get(msgClass);
                    }

                    newProcessor = new StaticProcessedDataSignal(channelSize, nChannels, nextMsgClass) {
                        @Override
                        public ProcessorType getType() {
                            return ProcessorType.TIME;
                        }

                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(params);
                        }

                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newConfig) {
                            ///< Do not allow time mode to be changed
                            Time timeConfig = (Time) newConfig;
                            ByteBuffer buffer = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).put((byte) 0x8)
                                    .put((byte) ((outputSize - 1) | (params.mode.ordinal() << 3))).putInt(timeConfig.period);
                            return buffer.array();
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            if (!(newConfig instanceof Time)) {
                                throw new ClassCastException("Can only swap the current configuration with another time configuration");
                            }

                            super.modifyConfiguration(newConfig);
                        }

                        @Override
                        public boolean isSigned() {
                            final boolean signed;
                            switch (params.mode) {
                                case ABSOLUTE:
                                    signed= parent.isSigned();
                                    break;
                                case DIFFERENTIAL:
                                    signed= true;
                                    break;
                                default:
                                    throw new RuntimeException("Exception here to quiet compile errors");
                            }

                            return signed;
                        }
                    };
                } else if (config instanceof Sample) {
                    final Sample params = (Sample) config;
                    newProcessor = new StaticProcessedDataSignal(channelSize, nChannels, msgClass) {
                        @Override
                        public void setState(State newState) {
                            throw new UnsupportedOperationException("Cannot change sample processor state");
                        }

                        @Override
                        public ProcessorType getType() {
                            return ProcessorType.SAMPLE;
                        }

                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(params);
                        }

                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newConfig) {
                            Sample sampleConfig = (Sample) newConfig;
                            return new byte[]{0xa, (byte) ((outputSize - 1) & 0x3), sampleConfig.binSize};
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            if (!(newConfig instanceof Sample)) {
                                throw new ClassCastException("Can only swap the current configuration with another sample configuration");
                            }

                            super.modifyConfiguration(newConfig);
                        }
                    };
                } else if (config instanceof Threshold) {
                    final Threshold params = (Threshold) config;
                    final byte size= (params.mode == Threshold.OutputMode.BINARY) ? 1 : outputSize;
                    final Class<? extends Message> nextClass= (params.mode == Threshold.OutputMode.BINARY) ? SignedMessage.class : msgClass;

                    newProcessor = new ProcessedDataSignal(size, nChannels, nextClass) {
                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(params);
                        }

                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newConfig) {
                            Threshold thsConfig = (Threshold) newConfig;

                            ///< Do not allow the threshold mode to be changed
                            byte second = (byte) ((DataSignalImpl.this.outputSize - 1) & 0x3 | (parent.isSigned() ? 0x4 : 0) |
                                    (params.mode.ordinal() << 3));
                            ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                            Number firmwareValue = parent.numberToFirmwareUnits(thsConfig.limit), firmwareHysteresis= parent.numberToFirmwareUnits(thsConfig.hysteresis);
                            buffer.put((byte) 0xd).put(second).putInt(firmwareValue.intValue()).putShort(firmwareHysteresis.shortValue());

                            return buffer.array();
                        }

                        @Override
                        public void setState(State newState) {
                            throw new UnsupportedOperationException("Cannot change threshold filter state");
                        }

                        @Override
                        public ProcessorType getType() {
                            return ProcessorType.THRESHOLD;
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            if (!(newConfig instanceof Threshold)) {
                                throw new ClassCastException("Can only swap the current configuration with another threshold configuration");
                            }
                            super.modifyConfiguration(newConfig);
                        }

                        @Override
                        public boolean isSigned() {
                            return params.mode == Threshold.OutputMode.BINARY || parent.isSigned();
                        }

                        @Override
                        public Number numberToFirmwareUnits(Number input) {
                            if (params.mode == Threshold.OutputMode.BINARY) {
                                return super.numberToFirmwareUnits(input);
                            }
                            return parent.numberToFirmwareUnits(input);
                        }

                        @Override
                        public DataSignal process(ProcessorConfig config) {
                            if (params.mode == Threshold.OutputMode.BINARY) {
                                return super.process(config);
                            }
                            return parent.process(config, this);
                        }

                        @Override
                        public DataSignal log(String key) {
                            if (params.mode == Threshold.OutputMode.BINARY) {
                                super.log(key, this);
                            } else {
                                parent.log(key, this);
                            }
                            return this;
                        }
                    };
                } else if (config instanceof Delta) {
                    final Delta params = (Delta) config;
                    final byte size = (params.mode == Delta.OutputMode.BINARY) ? 1 : outputSize;
                    final Class<? extends Message> nextClass;

                    switch (params.mode) {
                        case ABSOLUTE:
                            nextClass = msgClass;
                            break;
                        case DIFFERENTIAL:
                            if (unsignedToSigned.containsKey(msgClass)) {
                                nextClass = unsignedToSigned.get(msgClass);
                            } else {
                                nextClass = msgClass;
                            }
                            break;
                        case BINARY:
                            nextClass = SignedMessage.class;
                            break;
                        default:
                            throw new RuntimeException("Only here to quiet compiler error");
                    }

                    newProcessor = new ProcessedDataSignal(size, nChannels, nextClass) {
                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(params);
                        }

                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newConfig) {
                            Delta deltaConfig = (Delta) newConfig;

                            ///< Do not allow the delta mode to be changed
                            byte second = (byte) (((this.outputSize - 1) & 0x3) | (parent.isSigned() ? 0x4 : 0) |
                                    (params.mode.ordinal() << 3));
                            Number firmware = parent.numberToFirmwareUnits(deltaConfig.threshold);
                            ByteBuffer config = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).put((byte) 0xc).put(second)
                                    .putInt((int) firmware.longValue());
                            return config.array();

                        }

                        @Override
                        public void setState(State newState) {
                            if (!(newState instanceof Delta.State)) {
                                throw new ClassCastException("Delta processor state can only be modified with a Delta.State object");
                            }
                            super.setState(newState);
                        }

                        @Override
                        public ProcessorType getType() {
                            return ProcessorType.DELTA;
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            if (!(newConfig instanceof Delta)) {
                                throw new ClassCastException("Can only swap the current configuration with another delta configuration");
                            }
                            super.modifyConfiguration(newConfig);

                        }

                        @Override
                        public boolean isSigned() {
                            final boolean signed;
                            switch (params.mode) {
                                case ABSOLUTE:
                                    signed = parent.isSigned();
                                    break;
                                case DIFFERENTIAL:
                                case BINARY:
                                    signed = true;
                                    break;
                                default:
                                    throw new RuntimeException("Exception here to quiet compile errors");
                            }

                            return signed;
                        }

                        @Override
                        public Number numberToFirmwareUnits(Number input) {
                            if (params.mode == Delta.OutputMode.BINARY) {
                                return super.numberToFirmwareUnits(input);
                            }
                            return parent.numberToFirmwareUnits(input);
                        }

                        @Override
                        public DataSignal process(ProcessorConfig config) {
                            if (params.mode == Delta.OutputMode.BINARY) {
                                return super.process(config);
                            }
                            return parent.process(config, this);
                        }

                        @Override
                        public DataSignal log(String key) {
                            if (params.mode == Delta.OutputMode.BINARY) {
                                super.log(key, this);
                            } else {
                                parent.log(key, this);
                            }
                            return this;
                        }
                    };
                } else if (config instanceof Accumulator) {
                    final Accumulator params = (Accumulator) config;
                    final byte filterOutputSize = params.output == null ? outputSize : params.output;

                    newProcessor = new ProcessedDataSignal(filterOutputSize, nChannels, msgClass) {
                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newConfig) {
                            return new byte[]{0x2, (byte) (((this.outputSize - 1) & 0x3) | (((parent.outputSize - 1) & 0x3) << 2))};
                        }

                        @Override
                        public boolean isSigned() {
                            return parent.isSigned();
                        }

                        @Override
                        public Number numberToFirmwareUnits(Number input) {
                            return parent.numberToFirmwareUnits(input);
                        }

                        @Override
                        public void setState(State newState) {
                            if (!(newState instanceof Accumulator.State)) {
                                throw new RuntimeException("Accumulator state can only be modified with an Accumulator.State object");
                            }
                            super.setState(newState);
                        }

                        @Override
                        public ProcessorType getType() {
                            return ProcessorType.ACCUMULATOR;
                        }

                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(params);
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            throw new UnsupportedOperationException("Cannot change accumulator configuration");
                        }
                    };
                } else if (config instanceof Counter) {
                    final Counter params = (Counter) config;
                    newProcessor = new ProcessedDataSignal(params.size, nChannels, UnsignedMessage.class) {
                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(params);
                        }

                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newConfig) {
                            return new byte[]{0x2, (byte) (((this.outputSize - 1) & 0x3) | (((parent.outputSize - 1) & 0x3) << 2) | 0x10)};
                        }

                        @Override
                        public boolean isSigned() {
                            return false;
                        }

                        @Override
                        public void setState(State newState) {
                            if (!(newState instanceof Counter.State)) {
                                throw new RuntimeException("Counter state can only be modified with an Counter.State object");
                            }
                            super.setState(newState);
                        }

                        @Override
                        public ProcessorType getType() {
                            return ProcessorType.COUNTER;
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            throw new UnsupportedOperationException("Cannot change counter configuration");
                        }
                    };
                } else if (config instanceof Average) {
                    final Average params = (Average) config;
                    newProcessor = new ProcessedDataSignal(channelSize, nChannels, msgClass) {
                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(params);
                        }

                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newConfig) {
                            Average avgConfig = (Average) newConfig;
                            return new byte[]{0x3, (byte) (((outputSize - 1) & 0x3) | (((outputSize - 1) & 0x3) << 2)),
                                    avgConfig.sampleSize};
                        }

                        @Override
                        public boolean isSigned() {
                            return parent.isSigned();
                        }

                        @Override
                        public Number numberToFirmwareUnits(Number input) {
                            return parent.numberToFirmwareUnits(input);
                        }

                        @Override
                        public void setState(State newState) {
                            if (!(newState instanceof Average.State)) {
                                throw new RuntimeException("Average state can only be modified with an Average.State object");
                            }
                            super.setState(newState);
                        }

                        @Override
                        public ProcessorType getType() {
                            return ProcessorType.AVERAGE;
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            if (!(newConfig instanceof Average)) {
                                throw new ClassCastException("Can only swap the current configuration with another average configuration");
                            }

                            super.modifyConfiguration(newConfig);
                        }
                    };
                } else if (config instanceof Maths) {
                    final Maths params = (Maths) config;
                    final boolean signedOp = params.signed == null ? isSigned() : params.signed;
                    byte newChannelSize = channelSize;
                    Class<? extends Message> nextMsgClass;

                    switch (params.mathOp) {
                        case ADD:
                        case MULTIPLY:
                        case DIVIDE:
                        case SUBTRACT:
                        case MODULUS:
                            if (signedOp) {
                                if (unsignedToSigned.containsKey(msgClass)) {
                                    nextMsgClass = unsignedToSigned.get(msgClass);
                                } else {
                                    nextMsgClass = msgClass;
                                }
                            } else {
                                if (signedToUnsigned.containsKey(msgClass)) {
                                    nextMsgClass = signedToUnsigned.get(msgClass);
                                } else {
                                    nextMsgClass = msgClass;
                                }
                            }
                            break;
                        case ABS_VALUE:
                            if (signedToUnsigned.containsKey(msgClass)) {
                                nextMsgClass = signedToUnsigned.get(msgClass);
                            } else {
                                nextMsgClass = msgClass;
                            }
                            break;
                        case SQRT:
                            nextMsgClass = UnsignedMessage.class;
                            break;
                        case EXPONENT:
                            if (signedToUnsigned.containsKey(msgClass) || signedMsgClasses.contains(msgClass)) {
                                nextMsgClass = SignedMessage.class;
                            } else if (unsignedToSigned.containsKey(msgClass)) {
                                nextMsgClass = UnsignedMessage.class;
                            } else {
                                ///< Every class should be categorized as signed or unsigned except the three axis messages
                                ///< Should we also extend barometer and temp to follow suit?
                                nextMsgClass = msgClass;
                            }

                            if (signedOp && unsignedToSigned.containsKey(nextMsgClass)) {
                                nextMsgClass = unsignedToSigned.get(nextMsgClass);
                            }
                            break;
                        case CONSTANT:
                            if (signedOp) {
                                nextMsgClass = SignedMessage.class;
                            } else {
                                nextMsgClass = UnsignedMessage.class;
                            }
                            break;
                        default:
                            nextMsgClass = msgClass;
                            break;
                    }
                    switch (params.mathOp) {
                        case LEFT_SHIFT:
                            newChannelSize += (params.rhs.intValue() / 8);
                            if (newChannelSize > 4) {
                                newChannelSize = 4;
                            }
                            break;
                        case RIGHT_SHIFT:
                            newChannelSize -= (params.rhs.intValue() / 8);
                            if (newChannelSize <= 0) {
                                newChannelSize = 1;
                            }
                            break;
                        default:
                            if (nChannels == 1) {
                                newChannelSize = 4;
                            }
                            break;
                    }

                    newProcessor = new ProcessedDataSignal(newChannelSize, nChannels, nextMsgClass) {
                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(params);
                        }

                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newConfig) {
                            Maths mathsConfig = (Maths) newConfig;
                            Number firmwareRhs;
                            byte signedMask = (byte) (signedOp ? 0x10 : 0x0);

                            switch (mathsConfig.mathOp) {
                                case ADD:
                                case SUBTRACT:
                                case MODULUS:
                                    firmwareRhs = numberToFirmwareUnits(mathsConfig.rhs);
                                    break;
                                default:
                                    firmwareRhs = mathsConfig.rhs;
                            }

                            ///< Do not allow the math operation to be changed
                            if (firmwareVersion != null && firmwareVersion.compareTo(Constant.MULTI_CHANNEL_MATH) >= 0) {
                                ByteBuffer buffer = ByteBuffer.allocate(8);
                                buffer.order(ByteOrder.LITTLE_ENDIAN).put((byte) 0x9)
                                        .put((byte) ((this.channelSize - 1) & 0x3 | ((parent.channelSize - 1) << 2) | signedMask))
                                        .put((byte) params.mathOp.ordinal()).putInt(firmwareRhs.intValue())
                                        .put((byte) (this.nChannels - 1));
                                return buffer.array();
                            }
                            ByteBuffer buffer = ByteBuffer.allocate(7);
                            buffer.order(ByteOrder.LITTLE_ENDIAN).put((byte) 0x9)
                                    .put((byte) ((this.outputSize - 1) & 0x3 | ((parent.outputSize - 1) << 2) | signedMask))
                                    .put((byte) params.mathOp.ordinal()).putInt(firmwareRhs.intValue());
                            return buffer.array();
                        }

                        @Override
                        public boolean isSigned() {
                            switch(params.mathOp) {
                                case SQRT:
                                case ABS_VALUE:
                                    return false;
                                case SUBTRACT:
                                    return true;
                                default:
                                    return parent.isSigned();
                            }
                        }

                        @Override
                        public Number numberToFirmwareUnits(Number input) {
                            return parent.numberToFirmwareUnits(input);
                        }

                        @Override
                        public void setState(State newState) {
                            throw new UnsupportedOperationException("Cannot change math transformer state");
                        }

                        @Override
                        public ProcessorType getType() {
                            return ProcessorType.MATHS;
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            if (!(newConfig instanceof Maths)) {
                                throw new ClassCastException("Can only swap the current configuration with another math configuration");
                            }

                            Maths newMathsTransformer = (Maths) newConfig;
                            if (newMathsTransformer.rhsToken != null) {
                                eventParams = newMathsTransformer.rhsToken;
                                eventDestOffset = 4;
                            }

                            super.modifyConfiguration(newConfig);
                            eventParams = null;
                        }
                    };
                } else if (config instanceof Pulse) {
                    final Pulse params = (Pulse) config;
                    final byte newOutputSize;
                    final Class<? extends Message> newMessageClass;

                    switch (params.mode) {
                        case WIDTH:
                            newOutputSize = 2;
                            newMessageClass = UnsignedMessage.class;
                            break;
                        case AREA:
                            newOutputSize = 4;
                            newMessageClass = msgClass;
                            break;
                        case PEAK:
                            newOutputSize = outputSize;
                            newMessageClass = msgClass;
                            break;
                        default:
                            throw new RuntimeException("Added to clear IDE error");
                    }

                    newProcessor = new ProcessedDataSignal(newOutputSize, nChannels, newMessageClass) {
                        @Override
                        public ProcessorType getType() {
                            return ProcessorType.PULSE;
                        }

                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(params);
                        }

                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newConfig) {
                            Pulse pulseConfig = (Pulse) newConfig;
                            Number firmwareThs = parent.numberToFirmwareUnits(pulseConfig.threshold);

                            ///< Do not allow output type to switch
                            ByteBuffer buffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                                    .put((byte) 0xb)
                                    .put((byte) (parent.outputSize - 1))
                                    .put((byte) 0)
                                    .put((byte) params.mode.ordinal())
                                    .putInt(firmwareThs.intValue()).putShort(pulseConfig.width);
                            return buffer.array();
                        }

                        @Override
                        public boolean isSigned() {
                            return params.mode != Pulse.OutputMode.WIDTH && parent.isSigned();
                        }

                        @Override
                        public Number numberToFirmwareUnits(Number input) {
                            if (params.mode == Pulse.OutputMode.WIDTH) {
                                return super.numberToFirmwareUnits(input);
                            }
                            return parent.numberToFirmwareUnits(input);
                        }

                        @Override
                        public DataSignal process(ProcessorConfig config) {
                            if (params.mode == Pulse.OutputMode.WIDTH) {
                                return super.process(config);
                            }
                            return parent.process(config, this);
                        }

                        @Override
                        public DataSignal log(String key) {
                            if (params.mode == Pulse.OutputMode.WIDTH) {
                                super.log(key, this);
                            } else {
                                parent.log(key, this);
                            }
                            return this;
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            if (!(newConfig instanceof Pulse)) {
                                throw new ClassCastException("Can only swap the current configuration with another pulse configuration");
                            }

                            super.modifyConfiguration(newConfig);
                        }
                    };
                } else if (config instanceof Buffer) {
                    newProcessor = new StaticProcessedDataSignal(channelSize, nChannels, msgClass) {
                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(null);
                        }

                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newConfig) {
                            return new byte[]{0xf, (byte) (this.outputSize - 1)};
                        }

                        @Override
                        public void setState(State newState) {
                            throw new UnsupportedOperationException("Cannot change buffer state");
                        }

                        @Override
                        public ProcessorType getType() {
                            return ProcessorType.BUFFER;
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            throw new UnsupportedOperationException("Cannot change buffer configuration");
                        }
                    };
                } else if (config instanceof Rms || config instanceof Rss) {
                    final byte rmsMode, signedMask = (byte) (isSigned() ? 0x80 : 0x0);
                    final ProcessorType type;

                    if (config instanceof Rms) {
                        rmsMode = 0;
                        type = ProcessorType.RMS;
                    } else {
                        rmsMode = 1;
                        type = ProcessorType.RSS;
                    }
                    newProcessor = new ProcessedDataSignal(channelSize, (byte) 1, unpackedToRms.get(msgClass)) {
                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(null);
                        }

                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newConfig) {
                            return new byte[]{0x7,
                                    (byte) (((this.channelSize - 1) & 0x3) | (((parent.channelSize - 1) & 0x3) << 2) | (((parent.nChannels - 1) & 0x3) << 4) | signedMask),
                                    rmsMode};
                        }

                        @Override
                        public Number numberToFirmwareUnits(Number input) {
                            return parent.numberToFirmwareUnits(input);
                        }

                        @Override
                        public boolean isSigned() {
                            return false;
                        }

                        @Override
                        public void setState(State newState) {
                            throw new UnsupportedOperationException("Cannot change rms/rss transformer state");
                        }

                        @Override
                        public ProcessorType getType() {
                            return type;
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            throw new UnsupportedOperationException("Cannot change rms/rss configuration");
                        }
                    };
                } else {
                    return new InvalidDataSignal(new ClassCastException("Unrecognized ProcessorConfig subtype: \'" + config.getClass().toString() + "\'"));
                }

                postFilterCreate(newProcessor, parent);
                return newProcessor;
            }

            public Number numberToFirmwareUnits(Number input) {
                return input;
            }

            public byte[] getTriggerConfig() {
                byte[] eventCfg= getEventConfig();
                byte[] triggerCfg= new byte[eventCfg.length + 1];

                System.arraycopy(eventCfg, 0, triggerCfg, 0, eventCfg.length);
                triggerCfg[eventCfg.length]= (byte) (((outputSize - 1) << 5) | offset());
                return triggerCfg;
            }

            public abstract boolean isSigned();
            public abstract void enableNotifications();
            public abstract byte[] getEventConfig();

        }
        private abstract class DataSource extends DataSignalImpl {
            protected final ResponseHeader header;
            protected final ReadType type;

            protected DataSource(byte channelSize, byte nChannels, Class<? extends Message> msgClass, ResponseHeader header, ReadType type) {
                super(channelSize, nChannels, msgClass);
                this.header= header;
                this.type= type;
            }

            @Override
            public DataSignal stream(String key) {
                if (type == ReadType.SILENT) {
                    return new InvalidDataSignal(new UnsupportedOperationException("Cannot stream data from a silent read"));
                }
                return super.stream(key);
            }

            @Override
            public void unsubscribe() {
                responseProcessors.remove(header);
            }

            @Override
            public void resubscribe(RouteManager.MessageHandler processor) {
                enableNotifications();
                responseProcessors.put(header, processor);
            }

            @Override
            public byte[] getEventConfig() {
                return new byte[] {header.module, (byte) (type.mask() | header.register), header.id};
            }

            @Override
            public JSONObject serializeState() {
                try {
                    JSONObject subscriptionState= new JSONObject();

                    JSONArray notifyBytes= new JSONArray().put(header.module).put(header.register);
                    if (header.id != (byte) 0xff) {
                        notifyBytes.put(header.id);
                    }
                    notifyBytes.put(0x1);

                    JSONArray silenceBytes= new JSONArray().put(header.module).put(header.register);
                    if (header.id != (byte) 0xff) {
                        silenceBytes.put(header.id);
                    }
                    silenceBytes.put(0x0);

                    subscriptionState.put(JSON_FIELD_NOTIFY_BYTES, notifyBytes);
                    subscriptionState.put(JSON_FIELD_SILENCE_BYTES, silenceBytes);
                    subscriptionState.put(JSON_FIELD_HEADER, new JSONArray().put(header.module).put(header.register).put(header.id));
                    return subscriptionState;
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        private abstract class ProcessedDataSignal extends DataSignalImpl implements DataProcessor {
            private ResponseHeader header= null;

            protected ProcessedDataSignal(byte channelSize, byte nChannels, Class<? extends Message> msgClass) {
                super(channelSize, nChannels, msgClass);
            }

            @Override
            public void enableNotifications() {
                writeRegister(DataProcessorRegister.NOTIFY, (byte) 0x1);

                nProcSubscribers++;
                writeRegister(DataProcessorRegister.NOTIFY_ENABLE, header.id, (byte) 0x1);

                dataProcMsgClasses.put(header, msgClass);
            }

            @Override
            public void unsubscribe() {
                if (header != null) {
                    writeRegister(DataProcessorRegister.NOTIFY_ENABLE, header.id, (byte) 0);
                    responseProcessors.remove(header);
                }
                nProcSubscribers--;
                if (nProcSubscribers == 0) {
                    writeRegister(DataProcessorRegister.NOTIFY, (byte) 0x0);
                }
            }

            @Override
            public void resubscribe(RouteManager.MessageHandler processor) {
                enableNotifications();
                responseProcessors.put(header, processor);
            }

            @Override
            public byte[] getEventConfig() {
                if (header == null) {
                    throw new RuntimeException("ID has not been set yet");
                }
                return new byte[] {DataProcessorRegister.NOTIFY.moduleOpcode(), DataProcessorRegister.NOTIFY.opcode(), header.id};
            }

            @Override
            public void setState(State newState) {
                byte[] stateBytes;

                if (newState instanceof Accumulator.State) {
                    Number firmware= numberToFirmwareUnits(((Accumulator.State) newState).newRunningSum);

                    stateBytes= ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(firmware.intValue()).array();
                } else if (newState instanceof Average.State) {
                    stateBytes= new byte[0];
                } else if (newState instanceof Delta.State) {
                    Number firmware= parent.numberToFirmwareUnits(((Delta.State) newState).newPreviousValue);

                    stateBytes= ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(firmware.intValue()).array();
                } else if (newState instanceof Passthrough.State) {
                    stateBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(((Passthrough.State) newState).newValue).array();
                } else if (newState instanceof Counter.State) {
                    stateBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(((Counter.State) newState).newCount).array();
                } else {
                    throw new ClassCastException("Unrecognized state editor: \'" + newState.getClass() + "\'");
                }

                byte[] parameters= new byte[stateBytes.length + 1];
                System.arraycopy(stateBytes, 0, parameters, 1, stateBytes.length);
                parameters[0]= header.id;
                writeRegister(DataProcessorRegister.STATE, parameters);
            }

            public void setId(byte newId) {
                header= new ResponseHeader(DataProcessorRegister.NOTIFY, newId);
            }

            public abstract ProcessorType getType();
            public abstract byte[] getFilterConfig();
            protected abstract byte[] processorConfigToBytes(ProcessorConfig newConfig);

            @Override
            public void modifyConfiguration(ProcessorConfig newConfig) {
                byte[] configBytes= processorConfigToBytes(newConfig);

                byte[] modifyFilter = new byte[configBytes.length + 1];
                modifyFilter[0]= header.id;
                System.arraycopy(configBytes, 0, modifyFilter, 1, configBytes.length);

                writeRegister(DataProcessorRegister.PARAMETER, modifyFilter);
            }

            @Override
            public JSONObject serializeState() {
                if (header == null) {
                    throw new RuntimeException("ID has not been set yet");
                }

                try {
                    JSONObject subscriptionState= new JSONObject();

                    subscriptionState.put(JSON_FIELD_NOTIFY_BYTES,
                            new JSONArray().put(DataProcessorRegister.NOTIFY_ENABLE.moduleOpcode())
                                .put(DataProcessorRegister.NOTIFY_ENABLE.opcode()).put(header.id).put(0x1));
                    subscriptionState.put(JSON_FIELD_SILENCE_BYTES,
                            new JSONArray().put(DataProcessorRegister.NOTIFY_ENABLE.moduleOpcode())
                                .put(DataProcessorRegister.NOTIFY_ENABLE.opcode()).put(header.id).put(0x0));

                    subscriptionState.put(JSON_FIELD_MSG_CLASS, msgClass.getName());
                    subscriptionState.put(JSON_FIELD_HEADER, new JSONArray().put(header.module).put(header.register).put(header.id));
                    return subscriptionState;
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        private abstract class StaticProcessedDataSignal extends ProcessedDataSignal {
            public StaticProcessedDataSignal(byte channelSize, byte nChannels, Class<? extends Message> msgClass) {
                super(channelSize, nChannels, msgClass);
            }

            @Override
            public DataSignal process(ProcessorConfig config) {
                return parent.process(config, this);
            }

            @Override
            public DataSignal log(String key) {
                parent.log(key, this);
                return this;
            }

            @Override
            public Number numberToFirmwareUnits(Number input) {
                return parent.numberToFirmwareUnits(input);
            }

            @Override
            public boolean isSigned() {
                return parent.isSigned();
            }
        }
        private class EventSignal extends DataSource {
            private final Register eventRegister;

            public EventSignal(Register eventRegister) {
                super((byte) 0, (byte) 0, UnsignedMessage.class, new ResponseHeader(eventRegister), ReadType.NONE);
                this.eventRegister= eventRegister;
            }

            @Override
            public boolean isSigned() {
                return true;
            }

            @Override
            public DataSignal stream(String key) {
                return new InvalidDataSignal(new UnsupportedOperationException("Events must be transformed with a counter processor to be able to use the full DataSignal class"));
            }

            @Override
            public DataSignal log(String key) {
                return new InvalidDataSignal(new UnsupportedOperationException("Events must be transformed with a counter processor to be able to use the full DataSignal class"));
            }

            @Override
            protected DataSignal process(ProcessorConfig config, final DataSignalImpl parent) {
                if (config instanceof Counter) {
                    final Counter params= (Counter) config;
                    ProcessedDataSignal newProcessor= new ProcessedDataSignal(params.size, (byte) 1, UnsignedMessage.class) {
                        @Override
                        public byte[] getFilterConfig() {
                            return processorConfigToBytes(params);
                        }

                        @Override
                        protected byte[] processorConfigToBytes(ProcessorConfig newConfig) {
                            return new byte[]{0x2, (byte) (((this.outputSize - 1) & 0x3) | 0x10)};
                        }

                        @Override
                        public boolean isSigned() {
                            return false;
                        }

                        @Override
                        public void setState(State newState) {
                            if (!(newState instanceof Counter.State)) {
                                throw new RuntimeException("Counter state can only be modified with an Counter.State object");
                            }
                            super.setState(newState);
                        }

                        @Override
                        public ProcessorType getType() {
                            return ProcessorType.COUNTER;
                        }

                        @Override
                        public void modifyConfiguration(ProcessorConfig newConfig) {
                            throw new UnsupportedOperationException("Cannot change counter configuration");
                        }
                    };

                    postFilterCreate(newProcessor, parent);
                    return newProcessor;
                } else {
                    return new InvalidDataSignal(new UnsupportedOperationException("Events can only have a counter processor attached"));
                }
            }

            @Override
            public byte offset() {
                return 0;
            }

            @Override
            public void enableNotifications() {
                writeRegister(eventRegister, (byte) 1);
            }

            @Override
            public void unsubscribe() {
                writeRegister(eventRegister, (byte) 0);
                super.unsubscribe();
            }
        }
        private class DataProcessorStateSignal extends DataSource {
            private final RouteBuilder.ProcessedDataSignal signal;

            protected DataProcessorStateSignal(RouteBuilder.ProcessedDataSignal signal, Class<? extends Message> msgClass, boolean silent) {
                super(signal.channelSize, signal.nChannels, msgClass,
                        new ResponseHeader(InfoRegister.DATA_PROCESSOR.moduleOpcode(), DataProcessorRegister.STATE.opcode(), signal.header.id),
                        silent ? ReadType.SILENT : ReadType.NORMAL);
                this.signal= signal;
            }

            @Override
            public boolean isSigned() {
                return signal.isSigned();
            }

            @Override
            public void enableNotifications() {
                dataProcMsgClasses.put(header, msgClass);
            }
        }

        public DataSignal fromSingleChannelTemp(ReadType type) {
            return new DataSource((byte) 2, (byte) 1, TemperatureMessage.class, new ResponseHeader(TemperatureRegister.VALUE), type) {
                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * 8.0f;
                }

                @Override
                public boolean isSigned() {
                    return true;
                }

                @Override
                public void enableNotifications() { }
            };
        }
        public DataSignal fromMultiChannelTemp(final MultiChannelTemperature.Source src, ReadType type) {
            return new DataSource((byte) 2, (byte) 1, TemperatureMessage.class, new ResponseHeader(MultiChannelTempRegister.TEMPERATURE, src.channel()), type) {
                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * 8.0f;
                }

                @Override
                public boolean isSigned() {
                    return true;
                }

                @Override
                public void enableNotifications() { }

            };
        }

        public DataSignal fromSwitch() {
            return new DataSource((byte) 1, (byte) 1, UnsignedMessage.class, new ResponseHeader(SwitchRegister.STATE), ReadType.NONE) {
                @Override
                public boolean isSigned() {
                    return false;
                }

                @Override
                public void enableNotifications() {
                    writeRegister(SwitchRegister.STATE, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(SwitchRegister.STATE, (byte) 0);
                    super.unsubscribe();
                }
            };
        }

        private abstract class ThreeAxisDataSource extends DataSource {
            private ThreeAxisDataSource(Class<? extends Message> msgClass, ResponseHeader header) {
                super((byte) 2, (byte) 3, msgClass, header, ReadType.NONE);
            }

            @Override
            public boolean isSigned() {
                return true;
            }
        }
        private class SingleAxisDataSource extends DataSource {
            private final byte dataOffset;
            private final Register dataRegister;

            protected SingleAxisDataSource(byte offset, Class<? extends Message> msgClass, Register dataRegister) {
                super((byte) 2, (byte) 1, msgClass, new ResponseHeader(dataRegister), ReadType.NONE);
                dataOffset= offset;
                this.dataRegister= dataRegister;
            }

            @Override
            public boolean isSigned() {
                return true;
            }

            @Override
            public DataSignal stream(String key) {
                return new InvalidDataSignal(new UnsupportedOperationException("Subscribing to single axis sources is not supported.  Subscribe to the full data source instead"));
            }

            @Override
            public byte offset() {
                return dataOffset;
            }

            @Override
            public void enableNotifications() {
                writeRegister(dataRegister, (byte) 1);
            }

            @Override
            public void unsubscribe() {
                writeRegister(dataRegister, (byte) 0);
                super.unsubscribe();
            }
        }

        public DataSignal fromMma8452qAxis() {
            return new ThreeAxisDataSource(Mma8452qThreeAxisMessage.class, new ResponseHeader(Mma8452qAccelerometerRegister.DATA_VALUE)) {
                @Override
                public void enableNotifications() {
                    writeRegister(Mma8452qAccelerometerRegister.DATA_VALUE, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Mma8452qAccelerometerRegister.DATA_VALUE, (byte) 0);
                    super.unsubscribe();
                }

                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * 1000;
                }
            };
        }
        public DataSignal fromMma8452qXAxis() {
            return new SingleAxisDataSource(X_OFFSET, Mma8452qSingleAxisMessage.class, Mma8452qAccelerometerRegister.DATA_VALUE) {
                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * 1000;
                }
            };
        }
        public DataSignal fromMma8452qYAxis() {
            return new SingleAxisDataSource(Y_OFFSET, Mma8452qSingleAxisMessage.class, Mma8452qAccelerometerRegister.DATA_VALUE) {
                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * 1000;
                }
            };
        }
        public DataSignal fromMma8452qZAxis() {
            return new SingleAxisDataSource(Z_OFFSET, Mma8452qSingleAxisMessage.class, Mma8452qAccelerometerRegister.DATA_VALUE) {
                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * 1000;
                }
            };
        }
        public DataSignal fromMma8452qTap() {
            return new DataSource((byte) 1, (byte) 1, Mma8452qTapMessage.class, new ResponseHeader(Mma8452qAccelerometerRegister.PULSE_STATUS), ReadType.NONE) {
                @Override
                public boolean isSigned() { return false; }

                @Override
                public void enableNotifications() {
                    writeRegister(Mma8452qAccelerometerRegister.PULSE_STATUS, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Mma8452qAccelerometerRegister.PULSE_STATUS, (byte) 0);
                    super.unsubscribe();
                }
            };
        }
        public DataSignal fromMma8452qMovement() {
            return new DataSource((byte) 1, (byte) 1, Mma8452qMovementMessage.class, new ResponseHeader(Mma8452qAccelerometerRegister.MOVEMENT_VALUE), ReadType.NONE) {
                @Override
                public boolean isSigned() { return false; }

                @Override
                public void enableNotifications() {
                    writeRegister(Mma8452qAccelerometerRegister.MOVEMENT_VALUE, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Mma8452qAccelerometerRegister.MOVEMENT_VALUE, (byte) 0);
                    super.unsubscribe();
                }
            };
        }
        public DataSignal fromMma8452qOrientation() {
            return new DataSource((byte) 1, (byte) 1, Mma8452qOrientationMessage.class, new ResponseHeader(Mma8452qAccelerometerRegister.ORIENTATION_VALUE), ReadType.NONE) {
                @Override
                public boolean isSigned() { return false; }

                @Override
                public void enableNotifications() {
                    writeRegister(Mma8452qAccelerometerRegister.ORIENTATION_VALUE, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Mma8452qAccelerometerRegister.ORIENTATION_VALUE, (byte) 0);
                    super.unsubscribe();
                }
            };
        }
        public DataSignal fromMma8452qShake() {
            return new DataSource((byte) 1, (byte) 1, Mma8452qMovementMessage.class, new ResponseHeader(Mma8452qAccelerometerRegister.SHAKE_STATUS), ReadType.NONE) {
                @Override
                public boolean isSigned() { return false; }

                @Override
                public void enableNotifications() {
                    writeRegister(Mma8452qAccelerometerRegister.SHAKE_STATUS, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Mma8452qAccelerometerRegister.SHAKE_STATUS, (byte) 0);
                    super.unsubscribe();
                }
            };
        }

        public DataSignal fromBmi160Axis() {
            return new ThreeAxisDataSource(Bmi160ThreeAxisMessage.class, new ResponseHeader(Bmi160AccelerometerRegister.DATA_INTERRUPT)) {
                @Override
                public void unsubscribe() {
                    writeRegister(Bmi160AccelerometerRegister.DATA_INTERRUPT, (byte) 0);
                    super.unsubscribe();
                }

                @Override
                public void enableNotifications() {
                    writeRegister(Bmi160AccelerometerRegister.DATA_INTERRUPT, (byte) 0x1);
                }

                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * bmi160AccRange.scale();
                }
            };
        }
        public DataSignal fromBmi160XAxis() {
            return new SingleAxisDataSource(X_OFFSET, Bmi160SingleAxisMessage.class, Bmi160AccelerometerRegister.DATA_INTERRUPT) {
                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * bmi160AccRange.scale();
                }
            };
        }
        public DataSignal fromBmi160YAxis() {
            return new SingleAxisDataSource(Y_OFFSET, Bmi160SingleAxisMessage.class, Bmi160AccelerometerRegister.DATA_INTERRUPT) {
                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * bmi160AccRange.scale();
                }
            };
        }
        public DataSignal fromBmi160ZAxis() {
            return new SingleAxisDataSource(Z_OFFSET, Bmi160SingleAxisMessage.class, Bmi160AccelerometerRegister.DATA_INTERRUPT) {
                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * bmi160AccRange.scale();
                }
            };
        }
        public DataSignal fromBmi160Orientation() {
            return new DataSource((byte) 1, (byte) 1, Bmi160OrientationMessage.class, new ResponseHeader(Bmi160AccelerometerRegister.ORIENT_INTERRUPT), ReadType.NONE) {
                @Override
                public boolean isSigned() { return false; }

                @Override
                public void enableNotifications() {
                    writeRegister(Bmi160AccelerometerRegister.ORIENT_INTERRUPT, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Bmi160AccelerometerRegister.ORIENT_INTERRUPT, (byte) 0);
                    super.unsubscribe();
                }
            };
        }
        public DataSignal fromBmi160Flat() {
            return new DataSource((byte) 1,(byte) 1,  Bmi160FlatMessage.class, new ResponseHeader(Bmi160AccelerometerRegister.FLAT_INTERRUPT), ReadType.NONE) {
                @Override
                public boolean isSigned() { return false; }

                @Override
                public void enableNotifications() {
                    writeRegister(Bmi160AccelerometerRegister.FLAT_INTERRUPT, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Bmi160AccelerometerRegister.FLAT_INTERRUPT, (byte) 0);
                    super.unsubscribe();
                }
            };
        }
        public DataSignal fromBmi160StepDetection() {
            return new DataSource((byte) 1, (byte) 1, UnsignedMessage.class, new ResponseHeader(Bmi160AccelerometerRegister.STEP_DETECTOR_INTERRUPT), ReadType.NONE) {
                @Override
                public boolean isSigned() { return false; }

                @Override
                public void enableNotifications() {
                    writeRegister(Bmi160AccelerometerRegister.STEP_DETECTOR_INTERRUPT, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Bmi160AccelerometerRegister.STEP_DETECTOR_INTERRUPT, (byte) 0);
                    super.unsubscribe();
                }
            };
        }
        public DataSignal fromBmi160StepCounter(ReadType type) {
            return new DataSource((byte) 2, (byte) 1, UnsignedMessage.class, new ResponseHeader(Bmi160AccelerometerRegister.STEP_COUNTER_DATA), type) {
                @Override
                public boolean isSigned() { return false; }

                @Override
                public void enableNotifications() { }
            };
        }
        public DataSignal fromBmi160LowHigh() {
            return new DataSource((byte) 2, (byte) 1, Bmi160LowHighMessage.class, new ResponseHeader(Bmi160AccelerometerRegister.LOW_HIGH_G_INTERRUPT), ReadType.NONE) {
                @Override
                public boolean isSigned() { return false; }

                @Override
                public void enableNotifications() {
                    writeRegister(Bmi160AccelerometerRegister.LOW_HIGH_G_INTERRUPT, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Bmi160AccelerometerRegister.LOW_HIGH_G_INTERRUPT, (byte) 0);
                    super.unsubscribe();
                }
            };
        }
        public DataSignal fromBmi160Motion() {
            return new DataSource((byte) 2, (byte) 1, Bmi160MotionMessage.class, new ResponseHeader(Bmi160AccelerometerRegister.MOTION_INTERRUPT), ReadType.NONE) {
                @Override
                public boolean isSigned() { return false; }

                @Override
                public void enableNotifications() {
                    writeRegister(Bmi160AccelerometerRegister.MOTION_INTERRUPT, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Bmi160AccelerometerRegister.MOTION_INTERRUPT, (byte) 0);
                    super.unsubscribe();
                }
            };
        }
        public DataSignal fromBmi160Tap() {
            return new DataSource((byte) 2, (byte) 1, Bmi160TapMessage.class, new ResponseHeader(Bmi160AccelerometerRegister.TAP_INTERRUPT), ReadType.NONE) {
                @Override
                public boolean isSigned() { return false; }

                @Override
                public void enableNotifications() {
                    writeRegister(Bmi160AccelerometerRegister.TAP_INTERRUPT, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Bmi160AccelerometerRegister.TAP_INTERRUPT, (byte) 0);
                    super.unsubscribe();
                }
            };
        }

        public DataSignal fromBmi160Gyro() {
            return new ThreeAxisDataSource(Bmi160ThreeAxisGyroMessage.class, new ResponseHeader(Bmi160GyroRegister.DATA)) {
                @Override
                public void enableNotifications() {
                    writeRegister(Bmi160GyroRegister.DATA, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Bmi160GyroRegister.DATA, (byte) 0);
                    super.unsubscribe();
                }

                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * bmi160GyroRange.scale();
                }
            };
        }
        public DataSignal fromBmi160GyroXAxis() {
            return new SingleAxisDataSource(X_OFFSET, Bmi160SingleAxisGyroMessage.class, Bmi160GyroRegister.DATA) {
                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * bmi160GyroRange.scale();
                }
            };
        }
        public DataSignal fromBmi160GyroYAxis() {
            return new SingleAxisDataSource(Y_OFFSET, Bmi160SingleAxisGyroMessage.class, Bmi160GyroRegister.DATA) {
                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * bmi160GyroRange.scale();
                }
            };
        }
        public DataSignal fromBmi160GyroZAxis() {
            return new SingleAxisDataSource(Z_OFFSET, Bmi160SingleAxisGyroMessage.class, Bmi160GyroRegister.DATA) {
                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * bmi160GyroRange.scale();
                }
            };
        }

        public DataSignal fromAnalogGpio(final byte pin, final Gpio.AnalogReadMode mode, ReadType type) {
            final Register analogRegister;
            Class<? extends Message> msgClass= UnsignedMessage.class;

            switch(mode) {
                case ABS_REFERENCE:
                    analogRegister= GpioRegister.READ_AI_ABS_REF;
                    break;
                case ADC:
                    analogRegister= GpioRegister.READ_AI_ADC;
                    break;
                default:
                    analogRegister= null;
                    msgClass= null;
            }

            return new DataSource((byte) 2, (byte) 1, msgClass, new ResponseHeader(analogRegister, pin), type) {
                @Override
                public boolean isSigned() {
                    return false;
                }

                @Override
                public void enableNotifications() { }
            };
        }
        public DataSignal fromDigitalIn(final byte pin, ReadType type) {
            return new DataSource((byte) 1, (byte) 1, UnsignedMessage.class, new ResponseHeader(GpioRegister.READ_DI, pin), type) {
                @Override
                public boolean isSigned() {
                    return false;
                }

                @Override
                public void enableNotifications() { }
            };
        }
        public DataSignal fromGpioPinNotify(final byte pin) {
            return new DataSource((byte) 1, (byte) 1, UnsignedMessage.class, new ResponseHeader(GpioRegister.PIN_CHANGE_NOTIFY, pin), ReadType.NONE) {
                @Override
                public boolean isSigned() {
                    return false;
                }

                @Override
                public void enableNotifications() {
                    writeRegister(GpioRegister.PIN_CHANGE_NOTIFY, (byte) 1);
                }
            };
        }

        public DataSignal fromConductance(final byte channel, ReadType type) {
            return new DataSource((byte) 4, (byte) 1, UnsignedMessage.class, new ResponseHeader(ConductanceRegister.CONDUCTANCE, channel), type) {
                @Override
                public boolean isSigned() {
                    return false;
                }

                @Override
                public void enableNotifications() { }
            };
        }

        public DataSignal fromLtr329Output() {
            return new DataSource((byte) 4, (byte) 1, UnsignedMessage.class, new ResponseHeader(Ltr329AmbientLightRegister.OUTPUT), ReadType.NONE) {
                @Override
                public boolean isSigned() {
                    return false;
                }

                @Override
                public void enableNotifications() {
                    writeRegister(Ltr329AmbientLightRegister.OUTPUT, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Ltr329AmbientLightRegister.OUTPUT, (byte) 0);
                    super.unsubscribe();
                }
            };
        }

        public DataSignal fromBmp280Pressure() {
            return new DataSource((byte) 4, (byte) 1, Bmp280PressureMessage.class, new ResponseHeader(Bmp280BarometerRegister.PRESSURE), ReadType.NONE) {
                @Override
                public boolean isSigned() {
                    return false;
                }

                @Override
                public void enableNotifications() {
                    writeRegister(Bmp280BarometerRegister.PRESSURE, (byte) 1);
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Bmp280BarometerRegister.PRESSURE, (byte) 0);
                    super.unsubscribe();
                }

                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * 256.f;
                }
            };
        }
        public DataSignal fromBmp280Altitude() {
            return new DataSource((byte) 4, (byte) 1, Bmp280AltitudeMessage.class, new ResponseHeader(Bmp280BarometerRegister.ALTITUDE), ReadType.NONE) {
                @Override
                public boolean isSigned() {
                    return true;
                }

                @Override
                public void enableNotifications() {
                    writeRegister(Bmp280BarometerRegister.ALTITUDE, (byte) 1);
                }

                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * 256.f;
                }

                @Override
                public void unsubscribe() {
                    writeRegister(Bmp280BarometerRegister.ALTITUDE, (byte) 0);
                    super.unsubscribe();
                }
            };
        }

        public DataSignal fromI2C(byte numBytes, byte id) {
            return new DataSource(numBytes, (byte) 1, I2CMessage.class, new ResponseHeader(I2CRegister.READ_WRITE, id), ReadType.NORMAL) {
                @Override
                public void enableNotifications() { }

                @Override
                public boolean isSigned() {
                    return true;
                }

                @Override
                protected DataSignal process(ProcessorConfig config, final DataSignalImpl parent) {
                    if (!(config instanceof Counter)) {
                        return new InvalidDataSignal(new UnsupportedOperationException("Only processor supported for I2C data is the counter processor"));
                    }
                    return super.process(config, parent);
                }
            };
        }

        public DataSignal fromSettingsDisconnected() {
            if (firmwareVersion.compareTo(Constant.DISCONNECTED_EVENT) < 0) {
                return new InvalidDataSignal(new UnsupportedOperationException("Disconnected event only supported on firmware version 1.1.0 and later"));
            }
            return new EventSignal(SettingsRegister.DISCONNECT_EVENT);
        }

        public DataSignal fromSettingsBatteryState(ReadType type) {
            if (moduleInfo.get(InfoRegister.SETTINGS.moduleOpcode()).revision() < Constant.SETTINGS_BATTERY_REVISION) {
                return new InvalidDataSignal(new UnsupportedOperationException("Battery state only supported on firmware version 1.1.1 and later"));
            }
            return new DataSource((byte) 3, (byte) 1, BatteryStateMessage.class, new ResponseHeader(SettingsRegister.BATTERY_STATE), type) {
                @Override
                public boolean isSigned() {
                    return false;
                }

                @Override
                public void enableNotifications() { }

            };
        }

        public DataSignal fromDataProcessorState(String key, boolean silent) {
            if (dataProcessors.containsKey(key) && dataProcessors.get(key) instanceof RouteBuilder.ProcessedDataSignal) {
                final RouteBuilder.ProcessedDataSignal signal= (ProcessedDataSignal) dataProcessors.get(key);
                Class<? extends Message> stateMsgClass;

                switch (signal.getType()) {
                    case ACCUMULATOR:
                    case BUFFER:
                    case DELTA:
                        stateMsgClass= signal.msgClass;
                        break;
                    case AVERAGE:
                    case COUNTER:
                    case PASSTHROUGH:
                        stateMsgClass= UnsignedMessage.class;
                        break;
                    default:
                        return new InvalidDataSignal(new UnsupportedOperationException("No processor state available for type: " + signal.getType().toString()));
                }
                return new DataProcessorStateSignal(signal, stateMsgClass, silent);
            } else {
                return new InvalidDataSignal(new UnsupportedOperationException("No processor available with key: " + key));
            }
        }

        public DataSignal fromBmm150BField() {
            return new ThreeAxisDataSource(Bmm150ThreeAxisMessage.class, new ResponseHeader(Bmm150MagnetometerRegister.MAG_DATA)) {
                @Override
                public void unsubscribe() {
                    writeRegister(Bmm150MagnetometerRegister.MAG_DATA, (byte) 0);
                    super.unsubscribe();
                }

                @Override
                public void enableNotifications() {
                    writeRegister(Bmm150MagnetometerRegister.MAG_DATA, (byte) 0x1);
                }

                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * 16.f;
                }
            };
        }

        public DataSignal fromTcs34725Color(ReadType type) {
            return new DataSource((byte) 8, (byte) 1, Tcs34725ColorMessage.class, new ResponseHeader(Tcs34725ColorDetectorRegister.COLOR), type) {
                @Override
                public boolean isSigned() {
                    return false;
                }

                @Override
                public void enableNotifications() { }
            };
        }

        public DataSignal fromBme280Humidity(ReadType type) {
            return new DataSource((byte) 4, (byte) 1, Bme280HumidityMessage.class, new ResponseHeader(Bme280HumidityRegister.HUMIDITY), type) {
                @Override
                public boolean isSigned() {
                    return false;
                }

                @Override
                public void enableNotifications() { }

                @Override
                public Number numberToFirmwareUnits(Number input) {
                    return input.floatValue() * Bme280HumidityMessage.getScale();
                }
            };
        }

        public DataSignal fromTsl2671Proximity(ReadType type) {
            return new DataSource((byte) 2, (byte) 1, UnsignedMessage.class, new ResponseHeader(Tsl2671ProximityRegister.PROXIMITY), type) {
                @Override
                public boolean isSigned() {
                    return false;
                }

                @Override
                public void enableNotifications() { }
            };
        }

        @Override
        public void receivedId(byte id) {
            creators.poll().receivedId(id);
            addId();
        }

        private void eventCommandsCheck() {
            if (isConnected()) {
                if (nExpectedCmds == 0) {
                    currEventListener = null;
                    int routeId= routeIdGenerator.getAndIncrement();
                    manager.setRouteId(routeId);
                    conn.setResultReady(commitResult, manager, null);

                    updatePendingRoutes();
                }
            } else {
                conn.setResultReady(commitResult, null, new RuntimeException("Connection to MetaWear board lost"));
            }
        }

        public void receivedCommandId(byte id) {
            manager.routeEventCmdIds.add(id);
            nExpectedCmds--;
            eventCommandsCheck();
        }

        @Override
        public void begin() {
            final long routeTimeout= CREATOR_RESPONSE_TIME * creators.size() + EVENT_COMMAND_TIME;
            conn.setOpTimeout(commitResult, new Runnable() {
                @Override
                public void run() {
                    manager.remove();
                    conn.setResultReady(commitResult, null, new TimeoutException(String.format("Adding a data route timed out after %dms", routeTimeout)));
                    updatePendingRoutes();
                }
            }, routeTimeout);
            addId();
        }

        @Override
        public void queueCommand(byte[] command) {
            tapCommands.add(command);
        }

        private void addId() {
            if (creators.isEmpty()) {
                addTaps();
            } else {
                creators.peek().execute();
            }
        }

        private void addTaps() {
            eventConfig= null;
            currEventListener = this;
            for(Map.Entry<DataSignalImpl, DataSignal.ActivityHandler> it: signalMonitors.entrySet()) {
                eventConfig= it.getKey().getEventConfig();
                it.getValue().onSignalActive(dataProcessors, it.getKey());
            }
            signalMonitors.clear();
            eventConfig= null;

            for(byte[] it: tapCommands) {
                buildBlePacket(it);
            }
            tapCommands.clear();
            eventCommandsCheck();
        }

        public void onCommandWrite() {
            nExpectedCmds++;
        }
    }

    private interface ResponseProcessor {
        Response process(byte[] response);
    }
    private final HashMap<ResponseHeader, ResponseProcessor> responses;

    ///< General class variables
    private Version firmwareVersion;
    private final Connection conn;
    private final HashMap<Byte, ModuleInfo> moduleInfo= new HashMap<>();
    private Logging.DownloadHandler downloadHandler;
    private float notifyProgress= 1.0f;
    private long nLogEntries;
    private ReferenceTick latestTick;
    private final Map<Byte, Long> lastTimestamp= new HashMap<>();
    private final Map<Byte, ReferenceTick> logReferenceTicks= new HashMap<>();
    private final AtomicBoolean commitRoutes= new AtomicBoolean(false);
    private final Queue<RouteHandler> pendingRoutes= new LinkedList<>();
    private final Map<ResponseHeader, RouteManager.MessageHandler> responseProcessors= new ConcurrentHashMap<>();
    private final Map<ResponseHeader, Loggable> dataLoggers= new HashMap<>();

    ///< Route variables
    private final HashMap<Integer, RouteManagerImpl> dataRoutes= new HashMap<>();
    private final AtomicInteger routeIdGenerator= new AtomicInteger(0);
    private byte nProcSubscribers= 0;
    private final HashMap<ResponseHeader, Class<? extends Message>> dataProcMsgClasses= new HashMap<>();
    private final HashMap<String, DataProcessor> dataProcessors= new HashMap<>();
    private final HashMap<String, Subscription> dataSubscriptions= new HashMap<>();
    private final HashMap<String, Loggable> dataLoggerKeys= new HashMap<>();

    private void updatePendingRoutes() {
        pendingRoutes.poll();
        if (!pendingRoutes.isEmpty()) {
            pendingRoutes.peek().begin();
        } else {
            commitRoutes.set(false);

            if (currentBlock != null && !writingMacro.get()) {
                writingMacro.set(true);
                conn.executeTask(writeMacroCommands, WRITE_MACRO_DELAY);
            }
        }
    }

    ///< Timer variables
    private final HashMap<Byte, TimerControllerImpl> timerControllers= new HashMap<>();

    protected DefaultMetaWearBoard(final Connection conn) {
        this.conn= conn;
        final ResponseProcessor idProcessor= new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                if (!pendingRoutes.isEmpty()) {
                    pendingRoutes.peek().receivedId(response[2]);
                }
                return null;
            }
        };

        responses= new HashMap<>();
        responses.put(new ResponseHeader(SwitchRegister.STATE), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                byte[] respBody= new byte[response.length - 2];
                System.arraycopy(response, 2, respBody, 0, respBody.length);

                return new Response(new UnsignedMessage(respBody), new ResponseHeader(response[0], response[1]));
            }
        });
        responses.put(new ResponseHeader(EventRegister.ENTRY), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                if (currEventListener != null) {
                    currEventListener.receivedCommandId(response[2]);
                }
                return null;
            }
        });
        responses.put(new ResponseHeader(LoggingRegister.LENGTH), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                int payloadSize= response.length - 2;

                ByteBuffer buffer= ByteBuffer.wrap(response, 2, payloadSize).order(ByteOrder.LITTLE_ENDIAN);
                nLogEntries= (payloadSize > 2) ? buffer.getInt() & 0xffffffffL : buffer.getShort() & 0xffff;
                long nEntriesNotify= (long) (nLogEntries * notifyProgress);

                ///< TODO Have downloadLog return AsyncOperation<Long> to wrap uint32 size
                conn.setResultReady(logEntryCountResults.poll(), (int) nLogEntries, null);

                ByteBuffer readoutCommand= ByteBuffer.allocate(payloadSize + 4).order(ByteOrder.LITTLE_ENDIAN);
                ///< In little endian, [A, B, 0, 0] is equal to [A, B]
                readoutCommand.put(response, 2, payloadSize).putInt((int) nEntriesNotify);
                writeRegister(LoggingRegister.READOUT, readoutCommand.array());
                return null;
            }
        });
        responses.put(new ResponseHeader(LoggingRegister.READOUT_NOTIFY), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                processLogData(Arrays.copyOfRange(response, 2, 11));

                if (response.length == 20) {
                    processLogData(Arrays.copyOfRange(response, 11, 20));
                }
                return null;
            }
        });
        responses.put(new ResponseHeader(LoggingRegister.READOUT_PROGRESS), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                ByteBuffer buffer= ByteBuffer.wrap(response, 2, response.length - 2).order(ByteOrder.LITTLE_ENDIAN);
                final long nEntriesLeft= (response.length > 4) ? buffer.getInt() & 0xffffffffL : buffer.getShort() & 0xffff;

                if (nEntriesLeft == 0) {
                    lastTimestamp.clear();
                }
                if (downloadHandler != null) {
                    conn.executeTask(new Runnable() {
                        @Override
                        public void run() {
                            ///< TODO Upcast onProgressUpdate to long
                            downloadHandler.onProgressUpdate((int) nEntriesLeft, (int) nLogEntries);
                        }
                    });
                }
                return null;
            }
        });
        responses.put(new ResponseHeader(LoggingRegister.TRIGGER), idProcessor);
        responses.put(new ResponseHeader(DataProcessorRegister.ADD), idProcessor);
        responses.put(new ResponseHeader(DataProcessorRegister.NOTIFY), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                try {
                    byte[] respBody = new byte[response.length - 3];
                    System.arraycopy(response, 3, respBody, 0, respBody.length);
                    ResponseHeader header = new ResponseHeader(response[0], response[1], response[2]);

                    if (bmi160AccMessageClasses.contains(dataProcMsgClasses.get(header))) {
                        Constructor<?> cTor = dataProcMsgClasses.get(header).getConstructor(byte[].class, float.class);
                        return new Response((Message) cTor.newInstance(respBody, bmi160AccRange.scale()), header);
                    }

                    if (bmi160GyroMessageClasses.contains(dataProcMsgClasses.get(header))) {
                        Constructor<?> cTor = dataProcMsgClasses.get(header).getConstructor(byte[].class, float.class);
                        return new Response((Message) cTor.newInstance(respBody, bmi160GyroRange.scale()), header);
                    }

                    Constructor<?> cTor = dataProcMsgClasses.get(header).getConstructor(byte[].class);
                    return new Response((Message) cTor.newInstance(respBody), header);
                } catch (NullPointerException ex) {
                    return null;
                } catch (Exception ex) {
                    throw new RuntimeException("Cannot create a message processor for filter: " + Arrays.toString(response), ex);
                }
            }
        });
        responses.put(new ResponseHeader(DataProcessorRegister.STATE), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                try {
                    byte[] respBody = new byte[response.length - 3];
                    System.arraycopy(response, 3, respBody, 0, respBody.length);
                    ResponseHeader header = new ResponseHeader(response[0], response[1], response[2]);

                    if (bmi160AccMessageClasses.contains(dataProcMsgClasses.get(header))) {
                        Constructor<?> cTor = dataProcMsgClasses.get(header).getConstructor(byte[].class, float.class);
                        return new Response((Message) cTor.newInstance(respBody, bmi160AccRange.scale()), header);
                    }

                    if (bmi160GyroMessageClasses.contains(dataProcMsgClasses.get(header))) {
                        Constructor<?> cTor = dataProcMsgClasses.get(header).getConstructor(byte[].class, float.class);
                        return new Response((Message) cTor.newInstance(respBody, bmi160GyroRange.scale()), header);
                    }

                    Constructor<?> cTor = dataProcMsgClasses.get(header).getConstructor(byte[].class);
                    return new Response((Message) cTor.newInstance(respBody), header);
                } catch (NullPointerException ex) {
                    return null;
                } catch (Exception ex) {
                    throw new RuntimeException("Cannot create a message processor for filter state: " + Arrays.toString(response), ex);
                }
            }
        });
        responses.put(new ResponseHeader(TimerRegister.TIMER_ENTRY), new ResponseProcessor() {
            @Override
            public Response process(final byte[] response) {
                TimerControllerImpl timeCtrllr= new TimerControllerImpl(response[2], timeTasks.poll());
                timerControllers.put(response[2], timeCtrllr);

                pendingRoutes.add(timeCtrllr);
                if (!commitRoutes.get()) {
                    commitRoutes.set(true);
                    pendingRoutes.peek().begin();
                }

                return null;
            }
        });
        responses.put(new ResponseHeader(GpioRegister.READ_AI_ABS_REF), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                byte[] respBody= new byte[response.length - 3];
                System.arraycopy(response, 3, respBody, 0, respBody.length);
                ResponseHeader header= new ResponseHeader(response[0], response[1], response[2]);

                return new Response(new UnsignedMessage(respBody), header);
            }
        });
        responses.put(new ResponseHeader(GpioRegister.READ_AI_ADC), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                byte[] respBody= new byte[response.length - 3];
                System.arraycopy(response, 3, respBody, 0, respBody.length);
                ResponseHeader header= new ResponseHeader(response[0], response[1], response[2]);

                return new Response(new UnsignedMessage(respBody), header);
            }
        });
        responses.put(new ResponseHeader(GpioRegister.READ_DI), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                byte[] respBody= new byte[response.length - 3];
                System.arraycopy(response, 3, respBody, 0, respBody.length);
                ResponseHeader header= new ResponseHeader(response[0], response[1], response[2]);

                return new Response(new UnsignedMessage(respBody), header);
            }
        });
        responses.put(new ResponseHeader(GpioRegister.PIN_CHANGE_NOTIFY), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                byte[] respBody= new byte[response.length - 3];
                System.arraycopy(response, 3, respBody, 0, respBody.length);
                ResponseHeader header= new ResponseHeader(response[0], response[1], response[2]);

                return new Response(new UnsignedMessage(respBody), header);
            }
        });
        responses.put(new ResponseHeader(ConductanceRegister.CONDUCTANCE), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                byte[] respBody= new byte[response.length - 3];
                System.arraycopy(response, 3, respBody, 0, respBody.length);
                ResponseHeader header= new ResponseHeader(response[0], response[1], response[2]);

                return new Response(new UnsignedMessage(respBody), header);
            }
        });
        responses.put(new ResponseHeader(IBeaconRegister.UUID), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                ibeaconUuid= new UUID(ByteBuffer.wrap(response, 10, 8).order(ByteOrder.LITTLE_ENDIAN).getLong(),
                        ByteBuffer.wrap(response, 2, 8).order(ByteOrder.LITTLE_ENDIAN).getLong());
                readRegister(IBeaconRegister.MAJOR, false);
                return null;
            }
        });
        responses.put(new ResponseHeader(IBeaconRegister.MAJOR), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                ibeaconMajor = ByteBuffer.wrap(response, 2, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
                readRegister(IBeaconRegister.MINOR, false);
                return null;
            }
        });
        responses.put(new ResponseHeader(IBeaconRegister.MINOR), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                ibeaconMinor = ByteBuffer.wrap(response, 2, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
                readRegister(IBeaconRegister.RX_POWER, false);
                return null;
            }
        });
        responses.put(new ResponseHeader(IBeaconRegister.RX_POWER), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                ibeaconRxPower = response[2];
                readRegister(IBeaconRegister.TX_POWER, false);
                return null;
            }
        });
        responses.put(new ResponseHeader(IBeaconRegister.TX_POWER), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                ibeaconTxPower = response[2];
                readRegister(IBeaconRegister.PERIOD, false);
                return null;
            }
        });
        responses.put(new ResponseHeader(IBeaconRegister.PERIOD), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                ibeaconPeriod = ByteBuffer.wrap(response, 2, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
                conn.setResultReady(ibeaconConfigResults.poll(), new IBeacon.Configuration() {
                    @Override
                    public UUID adUuid() {
                        return ibeaconUuid;
                    }

                    @Override
                    public short major() {
                        return ibeaconMajor;
                    }

                    @Override
                    public short minor() {
                        return ibeaconMinor;
                    }

                    @Override
                    public byte rxPower() {
                        return ibeaconRxPower;
                    }

                    @Override
                    public byte txPower() {
                        return ibeaconTxPower;
                    }

                    @Override
                    public short adPeriod() {
                        return ibeaconPeriod;
                    }

                    @Override
                    public String toString() {
                        return String.format("{uuid: %s, major: %d, minor: %d, rx: %d, tx: %d, period: %d}",
                                ibeaconUuid.toString(), ibeaconMajor, ibeaconMinor, ibeaconRxPower, ibeaconTxPower, ibeaconPeriod);
                    }
                }, null);
                return null;
            }
        });
        responses.put(new ResponseHeader(SettingsRegister.DEVICE_NAME), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                byte[] respBody= new byte[response.length - 2];
                System.arraycopy(response, 2, respBody, 0, respBody.length);

                try {
                    advName= new String(respBody, "US-ASCII");
                } catch (UnsupportedEncodingException e) {
                    advName= new String(respBody);
                }
                readRegister(SettingsRegister.ADVERTISING_INTERVAL, false);
                return null;
            }
        });
        responses.put(new ResponseHeader(SettingsRegister.TX_POWER), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                advTxPower= response[2];
                readRegister(SettingsRegister.SCAN_RESPONSE, false);
                return null;
            }
        });
        responses.put(new ResponseHeader(SettingsRegister.SCAN_RESPONSE), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                advResponse = new byte[response.length - 2];
                System.arraycopy(response, 2, advResponse, 0, advResponse.length);
                conn.setResultReady(advertisementConfigResults.poll(), new Settings.AdvertisementConfig() {
                    @Override
                    public String deviceName() {
                        return advName;
                    }

                    @Override
                    public int interval() {
                        return advInterval;
                    }

                    @Override
                    public short timeout() {
                        return (short) (advTimeout & 0xff);
                    }

                    @Override
                    public byte txPower() {
                        return advTxPower;
                    }

                    @Override
                    public byte[] scanResponse() {
                        return advResponse;
                    }

                    @Override
                    public String toString() {
                        return String.format("{Device Name: %s, Adv Interval: %d, Adv Timeout: %d, Tx Power: %d, Scan Response: %s}",
                                advName, interval(), timeout(), advTxPower, arrayToHexString(advResponse));
                    }
                }, null);
                return null;
            }
        });
        responses.put(new ResponseHeader(I2CRegister.READ_WRITE), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                if (response[2] != (byte) 0xff) {
                    if (response.length > 3) {
                        byte[] i2cData = new byte[response.length - 3];
                        System.arraycopy(response, 3, i2cData, 0, response.length - 3);

                        conn.setResultReady(userI2cReads.get(response[2]).poll(), null, null);
                        return new Response(new I2CMessage(i2cData), new ResponseHeader(response[0], response[1], response[2]));
                    } else {
                        conn.setResultReady(userI2cReads.get(response[2]).poll(), null,
                                new RuntimeException("Error reading I2C data from device or register address.  Response: " + arrayToHexString(response)));
                    }
                } else {
                    if (response.length > 3) {
                        byte[] i2cData = new byte[response.length - 3];
                        System.arraycopy(response, 3, i2cData, 0, response.length - 3);

                        conn.setResultReady(i2cReadResults.poll(), i2cData, null);
                    } else {
                        conn.setResultReady(i2cReadResults.poll(), null,
                                new RuntimeException("Error reading I2C data from device or register address.  Response: " + arrayToHexString(response)));
                    }
                }
                return null;
            }
        });
        responses.put(new ResponseHeader(MacroRegister.BEGIN), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                macroIds.add(response[2]);
                return null;
            }
        });
        responses.put(new ResponseHeader(MacroRegister.NOTIFY), new ResponseProcessor() {
            @Override
            public Response process(byte[] response) {
                conn.setResultReady(macroExecResults.poll(), null, null);
                return null;
            }
        });
    }

    private void processLogData(byte[] logData) {
        final byte logId= (byte) (logData[0] & 0x1f), resetUid= (byte) ((logData[0] & 0xe0) >> 5);
        ReferenceTick reference= logReferenceTicks.containsKey(resetUid) ? logReferenceTicks.get(resetUid) : latestTick;
        long tick= ByteBuffer.wrap(logData, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
        final Calendar timestamp= (Calendar) reference.timestamp().clone();
        timestamp.add(Calendar.MILLISECOND, (int) ((tick - reference.tickCount()) * TICK_TIME_STEP));

        if (!lastTimestamp.containsKey(logId) || lastTimestamp.get(logId) < tick) {
            lastTimestamp.put(logId, tick);

            ResponseHeader header= new ResponseHeader(LoggingRegister.READOUT_NOTIFY, logId);
            final byte[] logEntryData= Arrays.copyOfRange(logData, 5, logData.length);

            if (dataLoggers.containsKey(header)) {
                dataLoggers.get(header).processLogMessage(logId, timestamp, logEntryData);
            } else {
                if (downloadHandler != null) {
                    conn.executeTask(new Runnable() {
                        @Override
                        public void run() {
                            downloadHandler.receivedUnknownLogEntry(logId, timestamp, logEntryData);
                        }
                    });
                }
            }
        }
    }

    public void receivedLoggingTick(byte[] response) {
        final long tick= ByteBuffer.wrap(response, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
        byte resetUid= (response.length > 6) ? response[6] : -1;

        latestTick= new ReferenceTick(resetUid, tick, Calendar.getInstance());
        if (resetUid != -1) {
            logReferenceTicks.put(latestTick.resetUid(), latestTick);
        }
    }
    public void receivedModuleInfo(ModuleInfo info) {
        moduleInfo.put(info.id(), info);

        if (info.id() == InfoRegister.TEMPERATURE.moduleOpcode()) {
            switch(info.implementation()) {
                case Constant.SINGLE_CHANNEL_TEMP_IMPLEMENTATION:
                    responses.put(new ResponseHeader(TemperatureRegister.VALUE), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody= new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new TemperatureMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    break;
                case Constant.MULTI_CHANNEL_TEMP_IMPLEMENTATION:
                    responses.put(new ResponseHeader(MultiChannelTempRegister.TEMPERATURE), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody= new byte[response.length - 3];
                            System.arraycopy(response, 3, respBody, 0, respBody.length);

                            return new Response(new TemperatureMessage(respBody), new ResponseHeader(response[0], response[1], response[2]));
                        }
                    });

                    byte[] extra= info.extra();
                    tempSources.clear();
                    for(byte i= 0; i < extra.length; i++) {
                        try {
                            Constructor<?> cTor = tempDriverClasses[extra[i]].getConstructor(DefaultMetaWearBoard.class, byte.class, byte.class);
                            tempSources.add((MultiChannelTemperature.Source) cTor.newInstance(this, extra[i], i));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                    break;
            }
        } else if (info.id() == InfoRegister.ACCELEROMETER.moduleOpcode()) {
            switch(info.implementation()) {
                case Constant.MMA8452Q_IMPLEMENTATION:
                    final ResponseProcessor movementProcessor= new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody= new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Mma8452qMovementMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    };

                    responses.put(new ResponseHeader(Mma8452qAccelerometerRegister.DATA_VALUE), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody= new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Mma8452qThreeAxisMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    responses.put(new ResponseHeader(Mma8452qAccelerometerRegister.PULSE_STATUS), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody= new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Mma8452qTapMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    responses.put(new ResponseHeader(Mma8452qAccelerometerRegister.ORIENTATION_VALUE), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody= new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Mma8452qOrientationMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    responses.put(new ResponseHeader(Mma8452qAccelerometerRegister.SHAKE_STATUS), movementProcessor);
                    responses.put(new ResponseHeader(Mma8452qAccelerometerRegister.MOVEMENT_VALUE), movementProcessor);
                    break;
                case Constant.BMI160_IMPLEMENTATION:
                case Constant.BMA255_IMPLEMENTATION:
                    responses.put(new ResponseHeader(Bmi160AccelerometerRegister.DATA_INTERRUPT), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody = new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Bmi160ThreeAxisMessage(respBody, bmi160AccRange.scale()),
                                    new ResponseHeader(response[0], response[1]));
                        }
                    });
                    responses.put(new ResponseHeader(Bmi160GyroRegister.DATA), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody = new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Bmi160ThreeAxisMessage(respBody, bmi160GyroRange.scale()),
                                    new ResponseHeader(response[0], response[1]));
                        }
                    });
                    responses.put(new ResponseHeader(Bmi160GyroRegister.CONFIG), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            bmi160DataSampling[0]= response[0];
                            bmi160DataSampling[1]= response[1];
                            bmi160GyroRange= Bmi160Gyro.FullScaleRange.bitMaskToRange((byte) (response[1] & 0x7));
                            return null;
                        }
                    });
                    responses.put(new ResponseHeader(Bmi160AccelerometerRegister.DATA_CONFIG), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            bmi160DataSampling[0]= response[0];
                            bmi160DataSampling[1]= response[1];
                            bma255DataSampling[0]= response[0];
                            bma255DataSampling[1]= response[1];
                            bmi160AccRange= Bmi160Accelerometer.AccRange.bitMaskToRange((byte) (response[1] & 0xf));
                            return null;
                        }
                    });
                    responses.put(new ResponseHeader(Bmi160AccelerometerRegister.ORIENT_INTERRUPT), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody= new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Bmi160OrientationMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    responses.put(new ResponseHeader(Bmi160AccelerometerRegister.FLAT_INTERRUPT), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody= new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Bmi160FlatMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    responses.put(new ResponseHeader(Bmi160AccelerometerRegister.STEP_DETECTOR_INTERRUPT), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody= new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new UnsignedMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    responses.put(new ResponseHeader(Bmi160AccelerometerRegister.STEP_COUNTER_DATA), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody= new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new UnsignedMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    responses.put(new ResponseHeader(Bmi160AccelerometerRegister.LOW_HIGH_G_INTERRUPT), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody= new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Bmi160LowHighMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    responses.put(new ResponseHeader(Bmi160AccelerometerRegister.MOTION_INTERRUPT), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody= new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Bmi160MotionMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    responses.put(new ResponseHeader(Bmi160AccelerometerRegister.TAP_INTERRUPT), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody= new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Bmi160TapMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    break;
            }
        } else if (info.id() == InfoRegister.BAROMETER.moduleOpcode()) {
            switch(info.implementation()) {
                case Constant.BMP280_BAROMETER:
                case Constant.BME280_BAROMETER:
                    responses.put(new ResponseHeader(Bmp280BarometerRegister.ALTITUDE), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody = new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Bmp280AltitudeMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    responses.put(new ResponseHeader(Bmp280BarometerRegister.PRESSURE), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody = new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Bmp280PressureMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    break;
            }
        } else if (info.id() == InfoRegister.AMBIENT_LIGHT.moduleOpcode()) {
            switch(info.implementation()) {
                case Constant.LTR329_LIGHT_SENSOR:
                    responses.put(new ResponseHeader(Ltr329AmbientLightRegister.OUTPUT), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody = new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new UnsignedMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    break;
            }
        } else if (info.id() == InfoRegister.SETTINGS.moduleOpcode()) {
            switch(info.revision()) {
                case 0:
                    responses.put(new ResponseHeader(SettingsRegister.ADVERTISING_INTERVAL), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            advInterval= ((response[2] & 0xff) | (response[3] << 8)) & 0xffff;
                            advTimeout= response[4];
                            readRegister(SettingsRegister.TX_POWER, false);
                            return null;
                        }
                    });
                    break;
                case Constant.SETTINGS_CONN_PARAMS_REVISION:
                case Constant.SETTINGS_DISCONNECTED_EVENT_REVISION:
                case Constant.SETTINGS_BATTERY_REVISION:
                    responses.put(new ResponseHeader(SettingsRegister.ADVERTISING_INTERVAL), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            int intervalBytes= ((response[2] & 0xff) | (response[3] << 8)) & 0xffff;

                            advInterval= (int) (intervalBytes * SettingsImpl.AD_INTERVAL_STEP);
                            advTimeout= response[4];
                            readRegister(SettingsRegister.TX_POWER, false);
                            return null;
                        }
                    });
                    responses.put(new ResponseHeader(SettingsRegister.CONNECTION_PARAMETERS), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            final ByteBuffer buffer= ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);

                            conn.setResultReady(connectionParameterResults.poll(), new Settings.ConnectionParameters() {
                                @Override
                                public float minConnectionInterval() {
                                    return buffer.getShort(2) * SettingsImpl.CONN_INTERVAL_STEP;
                                }

                                @Override
                                public float maxConnectionInterval() {
                                    return buffer.getShort(4) * SettingsImpl.CONN_INTERVAL_STEP;
                                }

                                @Override
                                public short slaveLatency() {
                                    return buffer.getShort(6);
                                }

                                @Override
                                public short supervisorTimeout() {
                                    return (short) (buffer.getShort(8) * SettingsImpl.SUPERVISOR_TIMEOUT_STEP);
                                }

                                @Override
                                public String toString() {
                                    return String.format("{min conn interval: %.2f, max conn interval: %.2f, slave latency: %d, supervisor timeout: %d}",
                                            minConnectionInterval(), maxConnectionInterval(), slaveLatency(), supervisorTimeout());
                                }
                            }, null);
                            return null;
                        }
                    });
                    break;
            }

            if (info.revision() == Constant.SETTINGS_BATTERY_REVISION) {
                responses.put(new ResponseHeader(SettingsRegister.BATTERY_STATE), new ResponseProcessor() {
                    @Override
                    public Response process(byte[] response) {
                        byte[] respBody = new byte[response.length - 2];
                        System.arraycopy(response, 2, respBody, 0, respBody.length);

                        return new Response(new BatteryStateMessage(respBody), new ResponseHeader(response[0], response[1]));
                    }
                });
            }
        } else if (info.id() == InfoRegister.LOGGING.moduleOpcode()) {
            switch(info.revision()) {
                case Constant.EXTENDED_LOGGING_REVISION:
                    responses.put(new ResponseHeader(LoggingRegister.READOUT_PAGE_COMPLETED), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            writeRegister(LoggingRegister.READOUT_PAGE_CONFIRM);
                            return null;
                        }
                    });
                    break;
            }
        } else if (info.id() == InfoRegister.MAGNETOMETER.moduleOpcode()) {
            switch(info.implementation()) {
                case Constant.BMM150_MAGNETOMETER:
                    responses.put(new ResponseHeader(Bmm150MagnetometerRegister.MAG_DATA), new ResponseProcessor() {
                        @Override
                        public Response process(byte[] response) {
                            byte[] respBody = new byte[response.length - 2];
                            System.arraycopy(response, 2, respBody, 0, respBody.length);

                            return new Response(new Bmm150ThreeAxisMessage(respBody), new ResponseHeader(response[0], response[1]));
                        }
                    });
                    break;
            }
        } else if (info.id() == InfoRegister.HUMIDITY.moduleOpcode()) {
            responses.put(new ResponseHeader(Bme280HumidityRegister.HUMIDITY), new ResponseProcessor() {
                @Override
                public Response process(byte[] response) {
                    byte[] respBody = new byte[response.length - 2];
                    System.arraycopy(response, 2, respBody, 0, respBody.length);

                    return new Response(new Bme280HumidityMessage(respBody), new ResponseHeader(response[0], response[1]));
                }
            });
        } else if (info.id() == InfoRegister.COLOR_DETECTOR.moduleOpcode()) {
            responses.put(new ResponseHeader(Tcs34725ColorDetectorRegister.COLOR), new ResponseProcessor() {
                @Override
                public Response process(byte[] response) {
                    byte[] respBody = new byte[response.length - 2];
                    System.arraycopy(response, 2, respBody, 0, respBody.length);

                    return new Response(new Tcs34725ColorMessage(respBody), new ResponseHeader(response[0], response[1]));
                }
            });
        } else if (info.id() == InfoRegister.PROXIMITY.moduleOpcode()) {
            responses.put(new ResponseHeader(Tsl2671ProximityRegister.PROXIMITY), new ResponseProcessor() {
                @Override
                public Response process(byte[] response) {
                    byte[] respBody = new byte[response.length - 2];
                    System.arraycopy(response, 2, respBody, 0, respBody.length);

                    return new Response(new UnsignedMessage(respBody), new ResponseHeader(response[0], response[1]));
                }
            });
        }
    }
    public void setFirmwareVersion(Version firmwareVersion) {
        this.firmwareVersion= firmwareVersion;
    }
    public void wroteCommand(byte[] cmd) {
        if (cmd[0] == InfoRegister.MACRO.moduleOpcode() &&
                (cmd[1] == MacroRegister.BEGIN.opcode() || cmd[1] == MacroRegister.END.opcode() || cmd[1] == MacroRegister.ADD_COMMAND.opcode())) {
            nExpectedMacroCmds--;
            if (nExpectedMacroCmds == 0) {
                conn.setResultReady(macroIdResults.poll(), macroIds.poll(), null);
            }
        }
    }

    public JSONObject serializePersistentState() {
        JSONObject persistentState= new JSONObject();

        try {
            JSONArray logTickStates= new JSONArray();
            for(ReferenceTick it: logReferenceTicks.values()) {
                logTickStates.put(it.serialize());
            }
            persistentState.put(JSON_FIELD_LOG_REFERENCE_TICKS, logTickStates);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return persistentState;
    }

    public void deserializePersistentState(JSONObject jsonState) {
        try {
            JSONArray logTickStates = jsonState.optJSONArray(JSON_FIELD_LOG_REFERENCE_TICKS);
            if (logTickStates != null) {
                for (int j = 0; j < logTickStates.length(); j++) {
                    ReferenceTick tick = new ReferenceTick(logTickStates.getJSONObject(j));
                    logReferenceTicks.put(tick.resetUid(), tick);
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] serializeState() {
        JSONObject boardState= new JSONObject();

        try {
            JSONArray logStates= new JSONArray();
            HashSet<String> seenLogKeys= new HashSet<>();
            for(Loggable it: dataLoggers.values()) {
                if (!seenLogKeys.contains(it.getKey())) {
                    seenLogKeys.add(it.getKey());
                    logStates.put(it.serializeState());
                }
            }

            JSONArray subscriberStates= new JSONArray();
            for(Map.Entry<String, Subscription> it: dataSubscriptions.entrySet()) {
                JSONObject partialState= it.getValue().serializeState();
                partialState.put(JSON_FIELD_NAME, it.getKey());
                subscriberStates.put(partialState);
            }

            JSONObject routeState= new JSONObject().put(JSON_FIELD_LOG, logStates)
                    .put(JSON_FIELD_SUBSCRIBERS, subscriberStates);

            JSONArray routeManagerState= new JSONArray();
            for(RouteManagerImpl it: dataRoutes.values()) {
                routeManagerState.put(it.serializeState());
            }

            JSONArray timerStates= new JSONArray();
            for(TimerControllerImpl it: timerControllers.values()) {
                timerStates.put(it.serialize());
            }

            boardState.put(JSON_FIELD_ROUTE, routeState).put(JSON_FIELD_TIMER, timerStates).put(JSON_FIELD_MMA8452Q_ODR, (mma8452qDataSampling[2] >> 3) & 0x3)
                    .put(JSON_FIELD_BMI160_ACC_RANGE, bmi160AccRange.ordinal()).put(JSON_FIELD_BMI160_GRYO_RANGE, bmi160GyroRange.ordinal())
                    .put(JSON_FIELD_ROUTE_MANAGER, routeManagerState);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        return boardState.toString().getBytes();
    }

    @Override
    public void deserializeState(byte[] state) {
        try {
            JSONObject jsonState= new JSONObject(new String(state));

            {
                JSONObject routeState = jsonState.getJSONObject(JSON_FIELD_ROUTE);
                JSONArray routeLoggerStates = routeState.getJSONArray(JSON_FIELD_LOG);

                for (byte j = 0; j < routeLoggerStates.length(); j++) {
                    JSONObject currentLogger = routeLoggerStates.getJSONObject(j);

                    Constructor<?> cTor = Class.forName(currentLogger.getString(Loggable.JSON_FIELD_HANDLER_CLASS)).getConstructor(DefaultMetaWearBoard.class, JSONObject.class);
                    Loggable newLogger = (Loggable) cTor.newInstance(this, currentLogger);

                    dataLoggerKeys.put(newLogger.getKey(), newLogger);
                }

                nProcSubscribers = 0;
                JSONArray subscriberStates = routeState.getJSONArray(JSON_FIELD_SUBSCRIBERS);
                for (byte j = 0; j < subscriberStates.length(); j++) {
                    final JSONObject currentSubscriber = subscriberStates.getJSONObject(j);


                    if (currentSubscriber.opt(JSON_FIELD_MSG_CLASS) != null) {
                        JSONArray headerState = currentSubscriber.getJSONArray(Subscription.JSON_FIELD_HEADER);
                        ResponseHeader header = new ResponseHeader((byte) headerState.getInt(0), (byte) headerState.getInt(1), (byte) headerState.getInt(2));

                        dataProcMsgClasses.put(header, (Class<Message>) Class.forName(currentSubscriber.getString(JSON_FIELD_MSG_CLASS)));
                        nProcSubscribers++;
                        dataSubscriptions.put(currentSubscriber.getString(JSON_FIELD_NAME), new ProcessorSubscription(currentSubscriber));
                    } else {
                        dataSubscriptions.put(currentSubscriber.getString(JSON_FIELD_NAME), new SensorSubscription(currentSubscriber));
                    }
                }
            }

            {
                int highestId= -1;
                JSONArray routeManagerStates= jsonState.getJSONArray(JSON_FIELD_ROUTE_MANAGER);

                for (byte j= 0; j < routeManagerStates.length(); j++) {
                    RouteManagerImpl currRouteManager= new RouteManagerImpl(routeManagerStates.getJSONObject(j));
                    if (currRouteManager.id() > highestId) {
                        highestId= currRouteManager.id();
                    }
                    dataRoutes.put(currRouteManager.id(), currRouteManager);
                }
                routeIdGenerator.set(highestId + 1);
            }

            {
                int mma8452qOdr= jsonState.getInt(JSON_FIELD_MMA8452Q_ODR);
                mma8452qDataSampling[2] &= 0xc7;
                mma8452qDataSampling[2] |= (mma8452qOdr) << 3;
            }

            {
                bmi160AccRange= Bmi160Accelerometer.AccRange.values()[jsonState.getInt(JSON_FIELD_BMI160_ACC_RANGE)];
                bmi160DataSampling[1]= bmi160AccRange.bitMask();
                bmi160GyroRange= Bmi160Gyro.FullScaleRange.values()[jsonState.getInt(JSON_FIELD_BMI160_GRYO_RANGE)];
                bmi160GyroConfig[1]= bmi160GyroRange.bitMask();
            }

            {
                JSONArray timerStates = jsonState.optJSONArray(JSON_FIELD_TIMER);
                for (byte j = 0; j < timerStates.length(); j++) {
                    TimerControllerImpl currentTimer= new TimerControllerImpl(timerStates.getJSONObject(j));
                    timerControllers.put(currentTimer.id(), currentTimer);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void removeRoutes() {
        for(Loggable it: dataLoggerKeys.values()) {
            it.remove();
        }
        dataLoggerKeys.clear();
        writeRegister(LoggingRegister.REMOVE_ALL);

        for(RouteManagerImpl manager: dataRoutes.values()) {
            for(Byte it: manager.routeEventCmdIds) {
                writeRegister(EventRegister.REMOVE, it);
            }
        }

        for(Subscription it: dataSubscriptions.values()) {
            it.unsubscribe();
        }
        dataSubscriptions.clear();

        writeRegister(DataProcessorRegister.REMOVE_ALL);
        writeRegister(DataProcessorRegister.NOTIFY, (byte) 0x0);
        dataProcessors.clear();
        dataRoutes.clear();
        nProcSubscribers= 0;
    }

    @Override
    public RouteManager getRouteManager(int id) {
        return dataRoutes.get(id);
    }

    public void receivedResponse(byte[] response) {
        response[1]&= 0x7f;
        ResponseHeader header= new ResponseHeader(response[0], response[1]);

        if (responses.containsKey(header)) {
            final Response resp= responses.get(header).process(response);
            if (resp != null && responseProcessors.containsKey(resp.header)) {
                final RouteManager.MessageHandler handler= responseProcessors.get(resp.header);
                conn.executeTask(new Runnable() {
                    @Override
                    public void run() {
                        handler.process(resp.body);
                    }
                });
            }
        }
    }

    private class SwitchImpl implements Switch {
        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromSensor() {
                    return new RouteBuilder().fromSwitch();
                }
            };
        }
    }

    private class ConductanceImpl implements Conductance {
        @Override
        public void readConductance(byte channel) {
            readConductance(channel, false);
        }

        @Override
        public void readConductance(byte channel, boolean silent) {
            readRegister(ConductanceRegister.CONDUCTANCE, silent, channel);
        }

        @Override
        public void calibrate() {
            writeRegister(ConductanceRegister.CALIBRATE);
        }

        @Override
        public DataSignal routeData(byte channel) {
            return new RouteBuilder().fromConductance(channel, ReadType.NORMAL);
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromChannel(byte channel, boolean silent) {
                    return new RouteBuilder().fromConductance(channel, silent ? ReadType.SILENT : ReadType.NORMAL);
                }
            };
        }
    }
    private class GsrImpl extends ConductanceImpl implements Gsr {
        @Override
        public ConfigEditor configure() {
            return new ConfigEditor() {
                private ConstantVoltage newCv= ConstantVoltage.CV_500MV;
                private Gain newGain= Gain.G_499K;

                @Override
                public ConfigEditor setConstantVoltage(ConstantVoltage cv) {
                    newCv= cv;
                    return this;
                }

                @Override
                public ConfigEditor setGain(Gain gain) {
                    newGain= gain;
                    return this;
                }

                @Override
                public void commit() {
                    writeRegister(ConductanceRegister.CONFIG, (byte) newCv.ordinal(), (byte) newGain.ordinal());
                }
            };
        }

    }
    private class LedImpl implements Led {
        @Override
        public ColorChannelEditor configureColorChannel(final ColorChannel channel) {
            return new ColorChannelEditor() {
                private final byte[] channelData= new byte[15];

                @Override
                public ColorChannelEditor setHighIntensity(byte intensity) {
                    channelData[2]= intensity;
                    return this;
                }

                @Override
                public ColorChannelEditor setLowIntensity(byte intensity) {
                    channelData[3]= intensity;
                    return this;
                }

                @Override
                public ColorChannelEditor setRiseTime(short time) {
                    channelData[5]= (byte)((time >> 8) & 0xff);
                    channelData[4]= (byte)(time & 0xff);
                    return this;
                }

                @Override
                public ColorChannelEditor setHighTime(short time) {
                    channelData[7]= (byte)((time >> 8) & 0xff);
                    channelData[6]= (byte)(time & 0xff);
                    return this;
                }

                @Override
                public ColorChannelEditor setFallTime(short time) {
                    channelData[9]= (byte)((time >> 8) & 0xff);
                    channelData[8]= (byte)(time & 0xff);
                    return this;
                }

                @Override
                public ColorChannelEditor setPulseDuration(short duration) {
                    channelData[11]= (byte)((duration >> 8) & 0xff);
                    channelData[10]= (byte)(duration & 0xff);
                    return this;
                }

                @Override
                public ColorChannelEditor setRepeatCount(byte count) {
                    channelData[14]= count;
                    return this;
                }

                @Override
                public void commit() {
                    channelData[0]= (byte)(channel.ordinal());
                    channelData[1]= 0x2;    ///< Keep it set to flash for now
                    writeRegister(LedRegister.MODE, channelData);
                }
            };
        }

        @Override
        public void play(boolean autoplay) {
            writeRegister(LedRegister.PLAY, (byte) (autoplay ? 2 : 1));
            Log.d("led","start/play");
        }

        @Override
        public void pause() {
            writeRegister(LedRegister.PLAY, (byte) 0);
            Log.d("led","pause");
        }

        @Override
        public void stop(boolean resetChannelAttrs) {
            writeRegister(LedRegister.STOP, (byte) (resetChannelAttrs ? 1 : 0));
            Log.d("led","stop");
        }
    }
    private class GpioImpl implements Gpio {
        @Override
        public void readAnalogIn(byte pin, AnalogReadMode mode) {
            readAnalogIn(pin, mode, false);
        }

        @Override
        public void readAnalogIn(byte pin, AnalogReadMode mode, boolean silent) {
            switch (mode) {
                case ABS_REFERENCE:
                    readRegister(GpioRegister.READ_AI_ABS_REF, silent, pin);
                    break;
                case ADC:
                    readRegister(GpioRegister.READ_AI_ADC, silent, pin);
                    break;
                //Log.d("readingAnlog", "implinging analogIn")
            }
        }

        @Override
         public void readDigitalIn(byte pin) {
            readDigitalIn(pin, false);
        }

        @Override
        public void readDigitalIn(byte pin, boolean silent) {
            readRegister(GpioRegister.READ_DI, silent, pin);
        }

        @Override
        public void setPinPullMode(byte pin, PullMode mode) {
            switch (mode) {
                case PULL_UP:
                    writeRegister(GpioRegister.PULL_UP_DI, pin);
                    break;
                case PULL_DOWN:
                    writeRegister(GpioRegister.PULL_DOWN_DI, pin);
                    break;
                case NO_PULL:
                    writeRegister(GpioRegister.NO_PULL_DI, pin);
                    break;
            }
        }

        @Override
        public void setDigitalOut(byte pin) {
            writeRegister(GpioRegister.SET_DO, pin);
        }

        @Override
        public void clearDigitalOut(byte pin) {
            writeRegister(GpioRegister.CLEAR_DO, pin);
        }

        @Override
        public void setPinChangeType(byte pin, PinChangeType type) {
            writeRegister(GpioRegister.PIN_CHANGE, pin, (byte) (type.ordinal() + 1));
        }

        @Override
        public void startPinChangeDetection(byte pin) {
            writeRegister(GpioRegister.PIN_CHANGE_NOTIFY_ENABLE, pin, (byte) 1);
        }

        @Override
        public void stopPinChangeDetection(byte pin) {
            writeRegister(GpioRegister.PIN_CHANGE_NOTIFY_ENABLE, pin, (byte) 0);
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromAnalogGpio(byte pin, AnalogReadMode mode) {
                    return fromAnalogIn(pin, mode);
                }

                @Override
                public DataSignal fromDigitalIn(byte pin) {
                    return fromDigitalIn(pin, false);
                }

                @Override
                public DataSignal fromDigitalInChange(byte pin) {
                    return new RouteBuilder().fromGpioPinNotify(pin);
                }

                @Override
                public DataSignal fromAnalogIn(byte pin, AnalogReadMode mode, boolean silent) {
                    return new RouteBuilder().fromAnalogGpio(pin, mode, silent ? ReadType.SILENT : ReadType.NORMAL);
                }

                @Override
                public DataSignal fromDigitalIn(byte pin, boolean silent) {
                    return new RouteBuilder().fromDigitalIn(pin, silent ? ReadType.SILENT : ReadType.NORMAL);
                }

                @Override
                public DataSignal fromGpioPinNotify(byte pin) {
                    return fromDigitalInChange(pin);
                }

                @Override
                public DataSignal fromAnalogIn(byte pin, AnalogReadMode mode) {
                    return fromAnalogIn(pin, mode, false);
                }
            };
        }
    }

    private final ConcurrentLinkedQueue<AsyncOperation<Timer.Controller>> timerControllerResults= new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Timer.Task> timeTasks= new ConcurrentLinkedQueue<>();
    private class TimerImpl implements Timer {
        @Override
        public AsyncOperation<Controller> scheduleTask(Task mwTask, int period, boolean delay) {
            return scheduleTask(mwTask, period, delay, (short) -1);
        }

        @Override
        public AsyncOperation<Controller> scheduleTask(final Task mwTask, int period, boolean delay, short repetitions) {
            final AsyncOperation<Controller> timerResult= conn.createAsyncOperation();
            if (isConnected()) {
                timeTasks.add(mwTask);
                timerControllerResults.add(timerResult);

                final long timerResponseTime= CREATOR_RESPONSE_TIME * 2;
                conn.setOpTimeout(timerResult, new Runnable() {
                    @Override
                    public void run() {
                        timerControllerResults.remove(timerResult);
                        timeTasks.remove(mwTask);
                        conn.setResultReady(timerResult, null, new TimeoutException(String.format("Creating a MetaWear timer timed out after %dms", timerResponseTime)));
                    }
                }, timerResponseTime);

                ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
                buffer.putInt(period).putShort(repetitions).put((byte) (delay ? 0 : 1));
                writeRegister(TimerRegister.TIMER_ENTRY, buffer.array());
            } else {
                conn.setResultReady(timerResult, null, new RuntimeException("Not connected to a MetaWear board"));
            }

            return timerResult;
        }

        @Override
        public void removeTimers() {
            for(TimerControllerImpl it: timerControllers.values()) {
                it.remove(false);
            }
            timerControllers.clear();
        }

        @Override
        public Controller getController(byte controllerId) {
            return timerControllers.get(controllerId);
        }
    }
    private class TimerControllerImpl implements EventListener, Timer.Controller, RouteHandler {
        private final static String JSON_FIELD_ID= "id";

        private final byte timerId;
        private final byte[] timerEventConfig;
        private byte nExpectedCommands= 0;
        private final HashSet<Byte> eventCmdIds;
        private boolean active= true;
        private final Queue<byte[]> taskCommands= new LinkedList<>();

        private Timer.Task task;

        public TimerControllerImpl(byte timerId, Timer.Task task) {
            this.timerId= timerId;
            this.task= task;

            timerEventConfig= new byte[] {TimerRegister.NOTIFY.moduleOpcode(), TimerRegister.NOTIFY.opcode(), timerId};
            eventCmdIds= new HashSet<>();
        }

        public TimerControllerImpl(JSONObject state) throws JSONException {
            timerId= (byte) state.getInt(JSON_FIELD_ID);

            JSONArray eventIds= state.getJSONArray(JSON_FIELD_CMD_IDS);
            eventCmdIds= new HashSet<>();
            for(int j= 0; j < eventIds.length(); j++) {
                eventCmdIds.add((byte) eventIds.getInt(j));
            }

            timerEventConfig= new byte[] {TimerRegister.NOTIFY.moduleOpcode(), TimerRegister.NOTIFY.opcode(), timerId};
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public byte id() {
            return timerId;
        }

        @Override
        public void start() {
            if (active) {
                writeRegister(TimerRegister.START, timerId);
                Log.d("led",", if running, then start it and start timer and write to register");
            }
        }

        @Override
        public void stop() {
            if (active) {
                writeRegister(TimerRegister.STOP, timerId);
                Log.d("led",", if running, then stop it and stop timer and write to register");
            }
        }

        @Override
        public void remove() {
            remove(true);
        }

        public void remove(boolean removeFromMap) {
            if (active) {
                active = false;

                writeRegister(TimerRegister.REMOVE, timerId);
                for (Byte id : eventCmdIds) {
                    writeRegister(EventRegister.REMOVE, id);
                }
                eventCmdIds.clear();
                if (removeFromMap) {
                    timerControllers.remove(timerId);
                }
            }
        }

        @Override
        public void queueCommand(byte[] command) {
            taskCommands.add(command);
        }

        @Override
        public void receivedCommandId(byte id) {
            eventCmdIds.add(id);
            nExpectedCommands--;

            if (nExpectedCommands == 0) {
                currEventListener = null;

                conn.setResultReady(timerControllerResults.poll(), this, null);
                updatePendingRoutes();
            }
        }

        @Override
        public void onCommandWrite() {
            nExpectedCommands++;
        }

        @Override
        public void begin() {
            final AsyncOperation<Timer.Controller> next= timerControllerResults.peek();

            conn.setOpTimeout(next, new Runnable() {
                @Override
                public void run() {
                    remove();
                    timerControllerResults.remove(next);
                    conn.setResultReady(next, null, new TimeoutException(String.format("Scheduling a MetaWear task timed out after %dms", EVENT_COMMAND_TIME)));
                }
            }, EVENT_COMMAND_TIME);

            currEventListener = this;
            eventConfig= timerEventConfig;
            task.commands();
            eventConfig= null;

            for(byte[] it: taskCommands) {
                conn.sendCommand(false, it);
            }
            taskCommands.clear();
        }

        @Override
        public void receivedId(byte id) { }

        public JSONObject serialize() {
            JSONObject state= new JSONObject();

            try {
                state.put(JSON_FIELD_ID, timerId);
                state.put(JSON_FIELD_CMD_IDS, new JSONArray(eventCmdIds));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }

            return state;
        }

    }
    private class DebugImpl implements Debug {
        @Override
        public void resetDevice() {
            writeRegister(DebugRegister.RESET_DEVICE);
        }

        @Override
        public void jumpToBootloader() {
            writeRegister(DebugRegister.JUMP_TO_BOOTLOADER);
        }

        @Override
        public void resetAfterGarbageCollect() {
            writeRegister(DebugRegister.DELAYED_RESET);
            disconnect();
        }

        @Override
        public void disconnect() {
            writeRegister(DebugRegister.GAP_DISCONNECT);
        }
    }

    private final ConcurrentLinkedQueue<AsyncOperation<Integer>> logEntryCountResults= new ConcurrentLinkedQueue<>();
    private class LoggingImpl implements Logging {
        @Override
        public void startLogging() {
            startLogging(false);
        }

        @Override
        public void startLogging(boolean overwrite) {
            writeRegister(LoggingRegister.CIRCULAR_BUFFER, (byte) (overwrite ? 1 : 0));
            writeRegister(LoggingRegister.ENABLE, (byte) 1);
        }

        @Override
        public void stopLogging() {
            writeRegister(LoggingRegister.ENABLE, (byte) 0);
        }

        @Override
        public AsyncOperation<Integer> downloadLog(float notifyProgress, DownloadHandler handler) {
            AsyncOperation<Integer> result= conn.createAsyncOperation();
            logEntryCountResults.add(result);

            DefaultMetaWearBoard.this.notifyProgress= notifyProgress;
            downloadHandler= handler;

            if (moduleInfo.get(InfoRegister.LOGGING.moduleOpcode()).revision() == Constant.EXTENDED_LOGGING_REVISION) {
                writeRegister(LoggingRegister.READOUT_PAGE_COMPLETED, (byte) 1);
            }
            writeRegister(LoggingRegister.READOUT_NOTIFY, (byte) 1);
            if (handler != null) {
                writeRegister(LoggingRegister.READOUT_PROGRESS, (byte) 1);
            }
            readRegister(LoggingRegister.LENGTH, false);

            return result;
        }

        @Override
        public void clearEntries() {
            clearEntries(0xffffffff);
        }

        @Override
        public void clearEntries(long nEntries) {
            if (moduleInfo.get(InfoRegister.LOGGING.moduleOpcode()).revision() == Constant.EXTENDED_LOGGING_REVISION) {
                writeRegister(LoggingRegister.READOUT_PAGE_COMPLETED, (byte) 1);
            }

            ByteBuffer buffer= ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) nEntries);
            writeRegister(LoggingRegister.REMOVE_ENTRIES, buffer.array());
        }

        @Override
        public long getLogCapacity() {
            byte[] extra= moduleInfo.get(InfoRegister.LOGGING.moduleOpcode()).extra();
            int payloadSize= extra.length - 1;

            ByteBuffer buffer= ByteBuffer.wrap(extra, 1, payloadSize).order(ByteOrder.LITTLE_ENDIAN);

            if (payloadSize > 2) {
                return buffer.getInt() & 0xffffffffL;
            }
            return buffer.getShort() & 0xffff;
        }
    }

    private UUID ibeaconUuid;
    private short ibeaconMajor, ibeaconMinor, ibeaconPeriod;
    private byte ibeaconRxPower, ibeaconTxPower;
    private final ConcurrentLinkedQueue<AsyncOperation<IBeacon.Configuration>> ibeaconConfigResults= new ConcurrentLinkedQueue<>();
    private class IBeaconImpl implements IBeacon {
        private static final long READ_CONFIG_TIMEOUT= 5000L;

        public ConfigEditor configure() {
            return new ConfigEditor() {
                private UUID newUuid= null;
                private Short newMajor= null, newMinor= null, newPeriod = null;
                private Byte newRxPower= null, newTxPower= null;

                @Override
                public ConfigEditor setUUID(UUID adUuid) {
                    newUuid = adUuid;
                    return this;
                }

                @Override
                public ConfigEditor setMajor(short major) {
                    newMajor = major;
                    return this;
                }

                @Override
                public ConfigEditor setMinor(short minor) {
                    newMinor = minor;
                    return this;
                }

                @Override
                public ConfigEditor setRxPower(byte power) {
                    newRxPower = power;
                    return this;
                }

                @Override
                public ConfigEditor setTxPower(byte power) {
                    newTxPower = power;
                    return this;
                }

                @Override
                public ConfigEditor setAdPeriod(short period) {
                    newPeriod = period;
                    return this;
                }

                @Override
                public void commit() {
                    if (newUuid != null) {
                        byte[] uuidBytes = ByteBuffer.allocate(16)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .putLong(newUuid.getLeastSignificantBits())
                                .putLong(newUuid.getMostSignificantBits())
                                .array();
                        writeRegister(IBeaconRegister.UUID, uuidBytes);
                    }

                    if (newMajor != null) {
                        writeRegister(IBeaconRegister.MAJOR, (byte) (newMajor & 0xff), (byte) ((newMajor >> 8) & 0xff));
                    }

                    if (newMinor != null) {
                        writeRegister(IBeaconRegister.MINOR, (byte) (newMinor & 0xff), (byte) ((newMinor >> 8) & 0xff));
                    }

                    if (newRxPower != null) {
                        writeRegister(IBeaconRegister.RX_POWER, newRxPower);
                    }

                    if (newTxPower != null) {
                        writeRegister(IBeaconRegister.TX_POWER, newTxPower);
                    }

                    if (newPeriod != null) {
                        writeRegister(IBeaconRegister.PERIOD, (byte) (newPeriod & 0xff), (byte) ((newPeriod >> 8) & 0xff));
                    }
                }
            };
        }

        public void enable() {
            writeRegister(IBeaconRegister.ENABLE, (byte) 1);
        }
        public void disable() {
            writeRegister(IBeaconRegister.ENABLE, (byte) 0);
        }

        public AsyncOperation<Configuration> readConfiguration() {
            final AsyncOperation<Configuration> result= conn.createAsyncOperation();

            ibeaconConfigResults.add(result);
            conn.setOpTimeout(result, new Runnable() {
                @Override
                public void run() {
                    ibeaconConfigResults.remove(result);
                    conn.setResultReady(result, null, new TimeoutException(String.format("IBeacon config read timed out after %dms", READ_CONFIG_TIMEOUT)));
                }
            }, READ_CONFIG_TIMEOUT);
            readRegister(IBeaconRegister.UUID, false);
            return result;
        }
    }

    private class HapticImpl implements Haptic {
        private final static float DEFAULT_DUTY_CYCLE= 100.f;

        @Override
        public void startMotor(short pulseWidth) {
            startMotor(DEFAULT_DUTY_CYCLE, pulseWidth);
        }

        @Override
        public void startMotor(float dutyCycle, short pulseWidth) {
            short converted= (short)((dutyCycle / 100.f) * 248);
            ByteBuffer buffer= ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).put((byte) (converted & 0xff)).putShort(pulseWidth).put((byte) 0);

            writeRegister(HapticRegister.PULSE, buffer.array());
        }

        @Override
        public void startBuzzer(short pulseWidth) {
            ByteBuffer buffer= ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).put((byte) 127).putShort(pulseWidth).put((byte) 1);
            writeRegister(HapticRegister.PULSE, buffer.array());
        }
    }
    private class NeoPixelImpl implements NeoPixel {
        @Override
        public void initializeStrand(byte strand, ColorOrdering ordering, StrandSpeed speed, byte gpioPin, byte length) {
            writeRegister(NeoPixelRegister.INITIALIZE, strand, (byte)(speed.ordinal() << 2 | ordering.ordinal()), gpioPin, length);
        }

        @Override
        public void deinitializeStrand(byte strand) {
            writeRegister(NeoPixelRegister.DEINITIALIZE, strand);
        }

        @Override
        public void holdStrand(byte strand) {
            writeRegister(NeoPixelRegister.HOLD, strand, (byte) 1);
        }

        @Override
        public void releaseHold(byte strand) {
            writeRegister(NeoPixelRegister.HOLD, strand, (byte) 0);
        }

        @Override
        public void clearStrand(byte strand, byte start, byte end) {
            writeRegister(NeoPixelRegister.CLEAR, strand, start, end);
        }

        @Override
        public void setPixel(byte strand, byte pixel, byte red, byte green, byte blue) {
            writeRegister(NeoPixelRegister.PIXEL, strand, pixel, red, green, blue);
        }

        @Override
        public void rotate(byte strand, RotationDirection direction, byte repetitions, short period) {
            writeRegister(NeoPixelRegister.ROTATE, strand, (byte)direction.ordinal(), repetitions,
                    (byte)(period & 0xff), (byte)(period >> 8 & 0xff));
        }

        @Override
        public void rotate(byte strand, RotationDirection direction, short period) {
            rotate(strand, direction, (byte) -1, period);
        }

        @Override
        public void stopRotation(byte strand) {
            writeRegister(NeoPixelRegister.ROTATE, new byte[] {strand, 0x0, 0x0, 0x0, 0x0});
        }
    }

    private String advName;
    private int advInterval;
    private byte advTimeout, advTxPower;
    private byte[] advResponse;
    private final ConcurrentLinkedQueue<AsyncOperation<Settings.AdvertisementConfig>> advertisementConfigResults= new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<AsyncOperation<Settings.ConnectionParameters>> connectionParameterResults= new ConcurrentLinkedQueue<>();
    private class SettingsImpl implements Settings {
        public static final float AD_INTERVAL_STEP= 0.625f, CONN_INTERVAL_STEP= 1.25f, SUPERVISOR_TIMEOUT_STEP= 10;
        private static final long READ_CONFIG_TIMEOUT= 5000L;

        @Override
        public ConnectionParameterEditor configureConnectionParameters() {
            return new ConnectionParameterEditor() {
                private Short minConnInterval= 6, maxConnInterval= 0x320, slaveLatency= 0, supervisorTimeout= 0x258;

                @Override
                public ConnectionParameterEditor setMinConnectionInterval(float interval) {
                    minConnInterval= (short) (interval / CONN_INTERVAL_STEP);
                    return this;
                }

                @Override
                public ConnectionParameterEditor setMaxConnectionInterval(float interval) {
                    maxConnInterval= (short) (interval / CONN_INTERVAL_STEP);
                    return this;
                }

                @Override
                public ConnectionParameterEditor setSlaveLatency(short latency) {
                    slaveLatency= latency;
                    return this;
                }

                @Override
                public ConnectionParameterEditor setSupervisorTimeout(short timeout) {
                    supervisorTimeout= (short) (timeout / SUPERVISOR_TIMEOUT_STEP);
                    return this;
                }

                @Override
                public void commit() {
                    ByteBuffer buffer= ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);

                    buffer.putShort(minConnInterval).putShort(maxConnInterval).putShort(slaveLatency).putShort(supervisorTimeout);
                    writeRegister(SettingsRegister.CONNECTION_PARAMETERS, buffer.array());
                }
            };
        }

        @Override
        public AsyncOperation<ConnectionParameters> readConnectionParameters() {
            final AsyncOperation<ConnectionParameters> result= conn.createAsyncOperation();

            if (moduleInfo.get(InfoRegister.SETTINGS.moduleOpcode()).revision() == 0) {
                conn.setResultReady(result, null, new UnsupportedOperationException("Connection parameters are not available for firmware v1.0.4 and earlier"));
            } else {
                connectionParameterResults.add(result);
                conn.setOpTimeout(result, new Runnable() {
                    @Override
                    public void run() {
                        connectionParameterResults.remove(result);
                        conn.setResultReady(result, null, new TimeoutException(String.format("Reading connection parameters timed out after %dms", READ_CONFIG_TIMEOUT)));
                    }
                }, READ_CONFIG_TIMEOUT);
                readRegister(SettingsRegister.CONNECTION_PARAMETERS, false);
            }
            return result;
        }

        @Override
        public ConfigEditor configure() {
            return new ConfigEditor() {
                private String newAdvName= null;
                private Short newAdvInterval= null;
                private Byte newAdvTimeout= null, newAdvTxPower= null;
                private byte[] newAdvResponse= null;

                @Override
                public ConfigEditor setDeviceName(String name) {
                    newAdvName = name;
                    return this;
                }

                @Override
                public ConfigEditor setAdInterval(short interval, byte timeout) {
                    newAdvInterval= interval;
                    newAdvTimeout = timeout;
                    return this;
                }

                @Override
                public ConfigEditor setTxPower(byte power) {
                    newAdvTxPower = power;
                    return this;
                }

                @Override
                public ConfigEditor setScanResponse(byte[] response) {
                    newAdvResponse = response;
                    return this;
                }

                @Override
                public void commit() {
                    if (newAdvName != null) {
                        try {
                            writeRegister(SettingsRegister.DEVICE_NAME, newAdvName.getBytes("US-ASCII"));
                        } catch (UnsupportedEncodingException e) {
                            writeRegister(SettingsRegister.DEVICE_NAME, newAdvName.getBytes());
                        }
                    }

                    if (newAdvInterval != null) {
                        if (moduleInfo.get(InfoRegister.SETTINGS.moduleOpcode()).revision() >= Constant.SETTINGS_CONN_PARAMS_REVISION) {
                            newAdvInterval = (short) ((newAdvInterval & 0xffff) / AD_INTERVAL_STEP);
                        }
                        writeRegister(SettingsRegister.ADVERTISING_INTERVAL, (byte) (newAdvInterval & 0xff),
                                (byte) ((newAdvInterval >> 8) & 0xff), newAdvTimeout);
                    }

                    if (newAdvTxPower != null) {
                        writeRegister(SettingsRegister.TX_POWER, newAdvTxPower);
                    }

                    if (newAdvResponse != null) {
                        if (newAdvResponse.length >= MW_COMMAND_LENGTH) {
                            byte[] first = new byte[13], second = new byte[newAdvResponse.length - 13];
                            System.arraycopy(newAdvResponse, 0, first, 0, first.length);
                            System.arraycopy(newAdvResponse, first.length, second, 0, second.length);

                            writeRegister(SettingsRegister.PARTIAL_SCAN_RESPONSE, first);
                            writeRegister(SettingsRegister.SCAN_RESPONSE, second);
                        } else {
                            writeRegister(SettingsRegister.SCAN_RESPONSE, newAdvResponse);
                        }
                    }
                }
            };
        }

        @Override
        public AsyncOperation<AdvertisementConfig> readAdConfig() {
            final AsyncOperation<AdvertisementConfig> result= conn.createAsyncOperation();

            advertisementConfigResults.add(result);
            conn.setOpTimeout(result, new Runnable() {
                @Override
                public void run() {
                    advertisementConfigResults.remove(result);
                    conn.setResultReady(result, null, new TimeoutException(String.format("Advertisement config read timed out after %dms", READ_CONFIG_TIMEOUT)));
                }
            }, READ_CONFIG_TIMEOUT);
            readRegister(SettingsRegister.DEVICE_NAME, false);
            return result;
        }

        @Override
        public void removeBond() {
            writeRegister(SettingsRegister.DELETE_BOND, (byte) 1);
        }

        @Override
        public void keepBond() {
            writeRegister(SettingsRegister.DELETE_BOND, (byte) 0);
        }

        @Override
        public void startAdvertisement() {
            writeRegister(SettingsRegister.START_ADVERTISEMENT);
        }

        @Override
        public void initiateBonding() {
            writeRegister(SettingsRegister.INIT_BOND);
        }

        @Override
        public EventSelector handleEvent() {
            return new EventSelector() {
                @Override
                public DataSignal fromDisconnect() {
                    return new RouteBuilder().fromSettingsDisconnected();
                }
            };
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromBattery() {
                    return fromBattery(false);
                }

                @Override
                public DataSignal fromBattery(boolean silent) {
                    return new RouteBuilder().fromSettingsBatteryState(silent ? ReadType.SILENT : ReadType.NORMAL);
                }
            };
        }

        @Override
        public void readBatteryState() {
            readBatteryState(false);
        }

        @Override
        public void readBatteryState(boolean silent) {
            readRegister(SettingsRegister.BATTERY_STATE, silent);
        }
    }

    private final Map<Byte, ConcurrentLinkedQueue<AsyncOperation<Void>>> userI2cReads= new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<AsyncOperation<byte[]>> i2cReadResults= new ConcurrentLinkedQueue<>();
    private class I2CImpl implements I2C {
        private final static long READ_DATA_TIMEOUT= 5000;

        @Override
        public void writeData(byte deviceAddr, byte registerAddr, byte[] data) {
            byte[] params= new byte[data.length + 4];
            params[0]= deviceAddr;
            params[1]= registerAddr;
            params[2]= (byte) 0xff;
            params[3]= (byte) data.length;
            System.arraycopy(data, 0, params, 4, data.length);

            writeRegister(I2CRegister.READ_WRITE, params);
        }

        @Override
        public AsyncOperation<byte[]> readData(byte deviceAddr, byte registerAddr, byte numBytes) {
            final AsyncOperation<byte[]> result= conn.createAsyncOperation();

            i2cReadResults.add(result);
            conn.setOpTimeout(result, new Runnable() {
                @Override
                public void run() {
                    i2cReadResults.remove(result);
                    conn.setResultReady(result, null, new TimeoutException(String.format("I2C read timed out after %dms", READ_DATA_TIMEOUT)));
                }
            }, READ_DATA_TIMEOUT);
            readRegister(I2CRegister.READ_WRITE, false, deviceAddr, registerAddr, (byte) 0xff, numBytes);
            return result;
        }

        @Override
        public AsyncOperation<Void> readData(byte deviceAddr, byte registerAddr, byte numBytes, final byte id) {
            final AsyncOperation<Void> result= conn.createAsyncOperation();
            if (!userI2cReads.containsKey(id)) {
                userI2cReads.put(id, new ConcurrentLinkedQueue<AsyncOperation<Void>>());
            }
            userI2cReads.get(id).add(result);
            conn.setOpTimeout(result, new Runnable() {
                @Override
                public void run() {
                    userI2cReads.get(id).remove(result);
                    conn.setResultReady(result, null, new TimeoutException(String.format("I2C read timed out after %dms", READ_DATA_TIMEOUT)));
                }
            }, READ_DATA_TIMEOUT);

            readRegister(I2CRegister.READ_WRITE, false, deviceAddr, registerAddr, id, numBytes);
            return result;
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromId(byte numBytes, byte id) {
                    return new RouteBuilder().fromI2C(numBytes, id);
                }
            };
        }
    }

    private final long WRITE_MACRO_DELAY = 2000L;
    private final Runnable writeMacroCommands= new Runnable() {
        @Override
        public void run() {
            saveCommand = false;

            nExpectedMacroCmds= commands.size() + 2;
            writeRegister(MacroRegister.BEGIN, (byte) (currentBlock.execOnBoot() ? 1 : 0));
            for (byte[] cmd : commands) {
                conn.sendCommand(true, cmd);
            }
            writeRegister(MacroRegister.END);
            currentBlock = null;
            writingMacro.set(false);
        }
    };
    private final AtomicBoolean writingMacro= new AtomicBoolean();
    private final Queue<byte[]> commands= new LinkedList<>();
    private final ConcurrentLinkedQueue<AsyncOperation<Byte>> macroIdResults = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<AsyncOperation<Void>> macroExecResults = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Byte> macroIds = new ConcurrentLinkedQueue<>();
    private int nExpectedMacroCmds= 0;
    private Macro.CodeBlock currentBlock;
    private class MacroImpl implements Macro {
        @Override
        public AsyncOperation<Byte> record(CodeBlock block) {
            AsyncOperation<Byte> idResult= conn.createAsyncOperation();

            currentBlock= block;
            macroIdResults.add(idResult);
            commands.clear();
            saveCommand= true;
            block.commands();

            if (!commitRoutes.get() && !writingMacro.get()) {
                writingMacro.set(true);
                conn.executeTask(writeMacroCommands, WRITE_MACRO_DELAY);
            }

            return idResult;
        }

        @Override
        public AsyncOperation<Void> execute(byte macroId) {
            AsyncOperation<Void> result= conn.createAsyncOperation();

            macroExecResults.add(result);
            writeRegister(MacroRegister.NOTIFY, (byte) 1);
            writeRegister(MacroRegister.NOTIFY_ENABLE, macroId, (byte) 1);
            writeRegister(MacroRegister.EXECUTE, macroId);

            return result;
        }

        @Override
        public void eraseMacros() {
            writeRegister(MacroRegister.ERASE_ALL);
        }
    }

    private Mma8452qAccelerometerImpl mma8452qModule= null;
    private final byte[] mma8452qDataSampling= new byte[] {0, 0, 0x18, 0, 0}, mma8452qOrientationCfg= new byte[5];
    private class Mma8452qAccelerometerImpl implements Mma8452qAccelerometer {
        private final float[][] motionCountSteps= new float[][] {
                {1.25f, 2.5f, 5, 10, 20, 20, 20, 20},
                {1.25f, 2.5f, 5, 10, 20, 80, 80, 80},
                {1.25f, 2.5f, 2.5f, 2.5f, 2.5f, 2.5f, 2.5f, 2.5f},
                {1.25f, 2.5f, 5, 10, 20, 80, 160, 160}
        }, transientSteps, orientationSteps;
        private final float[][][] pulseTmltSteps= new float[][][] {
            {{0.625f, 0.625f, 1.25f, 2.5f, 5, 5, 5, 5},
                {0.625f, 0.625f, 1.25f, 2.5f, 5, 20, 20, 20},
                {0.625f, 0.625f, 0.625f, 0.625f, 0.625f, 0.625f, 0.625f, 0.625f},
                {0.625f, 1.25f, 2.5f, 5, 10, 40, 40, 40}},
            {{1.25f, 2.5f, 5, 10, 20, 20, 20, 20},
                {1.25f, 2.5f, 5, 10, 20, 80, 80, 80},
                {1.25f, 2.5f, 2.5f, 2.5f, 2.5f, 2.5f, 2.5f, 2.5f},
                {1.25f, 2.5f, 5, 10, 20, 80, 160, 160}}
        }, pulseLtcySteps, pulseWindSteps;
        private final float[] sleepCountSteps= new float[] {320, 320, 320, 320, 320, 320, 320, 640};

        private final float MMA8452Q_G_PER_STEP= 0.063f;

        ///< Tap variables
        private float tapThreshold= 2.f;
        private int tapLatency= 200, tapWindow= 300, tapDuration= 60;
        private Axis tapAxis= Axis.Z;
        private TapType[] tapTypes= new TapType[] {TapType.SINGLE};

        ///< Shake variables
        private float shakeThreshold= 0.5f;
        private int shakeDuration= 50;
        private Axis shakeAxis= Axis.X;

        ///< Sampling variables
        private FullScaleRange selectedFsr= FullScaleRange.FSR_2G;

        ///< Movement variables
        private float movementThreshold= 1.5f;
        private int movementDuration= 100;
        private Axis[] movementAxes= Axis.values();
        private MovementType movementType= MovementType.MOTION;

        ///< Orientation variables
        private int orientationDelay= 100;

        ///< Global variables
        private int activeTimeout= 0;

        public Mma8452qAccelerometerImpl() {
            pulseLtcySteps= new float[2][4][8];
            for(int i= 0; i < pulseTmltSteps.length; i++) {
                for(int j= 0; j < pulseTmltSteps[i].length; j++) {
                    for(int k= 0; k < pulseTmltSteps[i][j].length; k++) {
                        pulseLtcySteps[i][j][k]= pulseTmltSteps[i][j][k] * 2.f;
                    }
                }
            }
            pulseWindSteps= pulseLtcySteps;
            transientSteps= motionCountSteps;
            orientationSteps= motionCountSteps;
        }

        @Override
        public float setOutputDataRate(float frequency) {
            final float[] values = new float[] {1.56f, 6.25f, 12.5f, 50.f, 100.f, 200.f, 400.f, 800.f};
            int selected = values.length - closestIndex(values, frequency) - 1;

            mma8452qDataSampling[2] &= 0xc7;
            mma8452qDataSampling[2] |= (selected) << 3;
            return values[selected];
        }

        @Override
        public float setAxisSamplingRange(float range) {
            final float[] ranges= new float[] {2.f, 4.f, 8.f};
            int selected= closestIndex(ranges, range);

            selectedFsr= FullScaleRange.values()[selected];
            return ranges[selected];
        }

        @Override
        public void setOutputDataRate(OutputDataRate rate) {
            mma8452qDataSampling[2] &= 0xc7;
            mma8452qDataSampling[2] |= rate.ordinal() << 3;
        }

        @Override
        public AutoSleepConfigEditor configureAutoSleep() {
            return new AutoSleepConfigEditor() {
                private SleepModeRate sleepRate;
                private int timeout;
                private PowerMode powerMode;

                @Override
                public AutoSleepConfigEditor setDataRate(SleepModeRate rate) {
                    sleepRate= rate;
                    return this;
                }

                @Override
                public AutoSleepConfigEditor setTimeout(int timeout) {
                    this.timeout= timeout;
                    return this;
                }

                @Override
                public AutoSleepConfigEditor setPowerMode(PowerMode pwrMode) {
                    powerMode= pwrMode;
                    return this;
                }

                @Override
                public void commit() {
                    Mma8452qAccelerometerImpl.this.activeTimeout= timeout;

                    mma8452qDataSampling[2] |= sleepRate.ordinal() << 6;
                    mma8452qDataSampling[3] &= ~(0x3 << 3);
                    mma8452qDataSampling[3] |= (powerMode.ordinal() << 3);
                }
            };
        }
        @Override
        public void enableAutoSleepMode() {
            mma8452qDataSampling[3] |= 0x4;
        }

        @Override
        public void disableAutoSleepMode() {
            mma8452qDataSampling[3] &= 0x4;
        }

        @Override
        public void setPowerMode(PowerMode mode) {
            mma8452qDataSampling[3] &= ~0x3;
            mma8452qDataSampling[3] |= mode.ordinal();
        }

        @Override
        public void enableAxisSampling() {
            writeRegister(Mma8452qAccelerometerRegister.DATA_ENABLE, (byte) 1);
        }

        @Override
        public void disableAxisSampling() {
            writeRegister(Mma8452qAccelerometerRegister.DATA_ENABLE, (byte) 0);
        }

        @Override
        public void start() {
            int accelOdr= (mma8452qDataSampling[2] >> 3) & 0x3;
            int pwMode= (mma8452qDataSampling[3] >> 3) & 0x3;

            {
                mma8452qDataSampling[0] &= 0xfc;
                mma8452qDataSampling[0] |= selectedFsr.ordinal();

                if ((mma8452qDataSampling[3] & 0x40) == 0x40) {
                    mma8452qDataSampling[4]= (byte)(activeTimeout / sleepCountSteps[accelOdr]);
                }

                writeRegister(Mma8452qAccelerometerRegister.DATA_CONFIG, mma8452qDataSampling);
            }

            {
                byte[] tapConfig = new byte[8];
                byte nSteps = (byte) (tapThreshold / MMA8452Q_G_PER_STEP);
                int lpfEn= (mma8452qDataSampling[1] & 0x10) >> 4;

                for(TapType it: tapTypes) {
                    switch(it) {
                        case SINGLE:
                            tapConfig[0] |= 1 << (2 * tapAxis.ordinal());
                            break;
                        case DOUBLE:
                            tapConfig[0] |= 1 << (1 + 2 * tapAxis.ordinal());
                            break;
                    }
                }
                tapConfig[0] |= 0x40;

                tapConfig[2] |= nSteps;
                tapConfig[3] |= nSteps;
                tapConfig[4] |= nSteps;
                tapConfig[5]= (byte) (tapDuration / pulseTmltSteps[lpfEn][pwMode][accelOdr]);
                tapConfig[6]= (byte) (tapLatency / pulseLtcySteps[lpfEn][pwMode][accelOdr]);
                tapConfig[7]= (byte) (tapWindow / pulseWindSteps[lpfEn][pwMode][accelOdr]);
                writeRegister(Mma8452qAccelerometerRegister.PULSE_CONFIG, tapConfig);
            }

            {
                byte[] shakeConfig= new byte[4];
                shakeConfig[0] = (byte) ((2 << shakeAxis.ordinal()) | 0x10);
                shakeConfig[2] = (byte) (shakeThreshold / MMA8452Q_G_PER_STEP);
                shakeConfig[3] = (byte)(shakeDuration / transientSteps[pwMode][accelOdr]);
                writeRegister(Mma8452qAccelerometerRegister.SHAKE_CONFIG, shakeConfig);
            }

            {
                byte[] movementConfig= new byte[4];

                movementConfig[0] |= 0x80;
                if (movementType == MovementType.MOTION) {
                    movementConfig[0] |= 0x40;
                }

                byte mask= 0;
                for(Axis it: movementAxes) {
                    mask |= 1 << (it.ordinal() + 3);
                }
                movementConfig[0] |= mask;
                movementConfig[2]= (byte) (movementThreshold / MMA8452Q_G_PER_STEP);
                movementConfig[3]= (byte)(movementDuration / transientSteps[pwMode][accelOdr]);
                writeRegister(Mma8452qAccelerometerRegister.MOVEMENT_CONFIG, movementConfig);
            }

            {
                mma8452qOrientationCfg[2]= (byte) (orientationDelay / orientationSteps[pwMode][accelOdr]);
                writeRegister(Mma8452qAccelerometerRegister.ORIENTATION_CONFIG, mma8452qOrientationCfg);
            }

            writeRegister(Mma8452qAccelerometerRegister.GLOBAL_ENABLE, (byte) 1);
        }

        @Override
        public void stop() {
            writeRegister(Mma8452qAccelerometerRegister.GLOBAL_ENABLE, (byte) 0);
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromAxes() {
                    return new RouteBuilder().fromMma8452qAxis();
                }

                @Override
                public DataSignal fromXAxis() {
                    return new RouteBuilder().fromMma8452qXAxis();
                }

                @Override
                public DataSignal fromYAxis() {
                    return new RouteBuilder().fromMma8452qYAxis();
                }

                @Override
                public DataSignal fromZAxis() {
                    return new RouteBuilder().fromMma8452qZAxis();
                }

                @Override
                public DataSignal fromTap() {
                    return new RouteBuilder().fromMma8452qTap();
                }

                @Override
                public DataSignal fromOrientation() {
                    return new RouteBuilder().fromMma8452qOrientation();
                }

                @Override
                public DataSignal fromShake() {
                    return new RouteBuilder().fromMma8452qShake();
                }

                @Override
                public DataSignal fromMovement() {
                    return new RouteBuilder().fromMma8452qMovement();
                }
            };
        }

        @Override
        public SamplingConfigEditor configureAxisSampling() {
            return new SamplingConfigEditor() {
                private final byte DISABLE_HPF= -1;
                private FullScaleRange selectedFsr= FullScaleRange.FSR_2G;
                private byte hpfCutoff= DISABLE_HPF;

                @Override
                public SamplingConfigEditor setFullScaleRange(FullScaleRange range) {
                    selectedFsr= range;
                    return this;
                }

                @Override
                public SamplingConfigEditor enableHighPassFilter(byte cutoff) {
                    hpfCutoff= cutoff;
                    return this;
                }

                @Override
                public void commit() {
                    if (hpfCutoff != DISABLE_HPF) {
                        mma8452qDataSampling[0] |= 0x10;
                        mma8452qDataSampling[1] |= (hpfCutoff & 0x3);
                    } else {
                        mma8452qDataSampling[0] &= ~0x10;
                    }
                    Mma8452qAccelerometerImpl.this.selectedFsr= this.selectedFsr;
                }
            };
        }

        @Override
        public TapConfigEditor configureTapDetection() {
            return new TapConfigEditor() {
                private float tapThreshold= 2.f;
                private int tapLatency= 200, tapWindow= 300, tapDuration= 60;
                private boolean enableLowPassFilter= false;
                private Axis tapAxis= Axis.Z;

                @Override
                public TapConfigEditor setLatency(int latency) {
                    tapLatency= latency;
                    return this;
                }

                @Override
                public TapConfigEditor setWindow(int window) {
                    tapWindow= window;
                    return this;
                }

                @Override
                public TapConfigEditor enableLowPassFilter() {
                    enableLowPassFilter= true;
                    return this;
                }

                @Override
                public TapConfigEditor setAxis(Axis axis) {
                    tapAxis= axis;
                    return this;
                }

                @Override
                public TapConfigEditor setThreshold(float threshold) {
                    tapThreshold= threshold;
                    return this;
                }

                @Override
                public TapConfigEditor setDuration(int duration) {
                    tapDuration= duration;
                    return this;
                }

                @Override
                public void commit() {
                    Mma8452qAccelerometerImpl.this.tapAxis= this.tapAxis;
                    Mma8452qAccelerometerImpl.this.tapThreshold= this.tapThreshold;
                    Mma8452qAccelerometerImpl.this.tapLatency= this.tapLatency;
                    Mma8452qAccelerometerImpl.this.tapWindow= this.tapWindow;
                    Mma8452qAccelerometerImpl.this.tapDuration= this.tapDuration;

                    if (enableLowPassFilter) {
                        mma8452qDataSampling[1] |= 0x10;
                    } else {
                        mma8452qDataSampling[1] &= ~0x10;
                    }
                }
            };
        }

        @Override
        public void enableTapDetection(TapType ... types) {
            tapTypes= new TapType[types.length];
            System.arraycopy(types, 0, tapTypes, 0, types.length);

            writeRegister(Mma8452qAccelerometerRegister.PULSE_ENABLE, (byte) 1);
        }

        @Override
        public void disableTapDetection() {
            writeRegister(Mma8452qAccelerometerRegister.PULSE_ENABLE, (byte) 0);
        }

        @Override
        public ShakeConfigEditor configureShakeDetection() {
            return new ShakeConfigEditor() {
                private float shakeThreshold= 0.5f;
                private int shakeDuration= 50;
                private Axis shakeAxis;

                @Override
                public ShakeConfigEditor setThreshold(float threshold) {
                    shakeThreshold= threshold;
                    return this;
                }

                @Override
                public ShakeConfigEditor setDuration(int duration) {
                    shakeDuration= duration;
                    return this;
                }

                @Override
                public void commit() {
                    Mma8452qAccelerometerImpl.this.shakeAxis= shakeAxis;
                    Mma8452qAccelerometerImpl.this.shakeDuration= this.shakeDuration;
                    Mma8452qAccelerometerImpl.this.shakeThreshold= this.shakeThreshold;
                }

                @Override
                public ShakeConfigEditor setAxis(Axis axis) {
                    this.shakeAxis= axis;
                    return this;
                }
            };
        }

        @Override
        public void enableShakeDetection() {
            writeRegister(Mma8452qAccelerometerRegister.SHAKE_ENABLE, (byte) 1);
        }

        @Override
        public void disableShakeDetection() {
            writeRegister(Mma8452qAccelerometerRegister.SHAKE_ENABLE, (byte) 0);
        }

        private class MovementConfigEditorImpl implements MovementConfigEditor {
            private float movementThreshold;
            private int movementDuration= 100;
            private Axis[] movementAxes= Axis.values();

            public MovementConfigEditorImpl(MovementType type) {
                movementThreshold= (type == MovementType.FREE_FALL ? 0.5f : 1.5f);
            }
            @Override
            public MovementConfigEditor setThreshold(float threshold) {
                movementThreshold= threshold;
                return this;
            }

            @Override
            public MovementConfigEditor setDuration(int duration) {
                movementDuration= duration;
                return this;
            }

            @Override
            public void commit() {
                Mma8452qAccelerometerImpl.this.movementAxes= movementAxes;
                Mma8452qAccelerometerImpl.this.movementThreshold= this.movementThreshold;
                Mma8452qAccelerometerImpl.this.movementDuration= this.movementDuration;
            }

            @Override
            public MovementConfigEditor setAxes(Axis ... axes) {
                movementAxes= new Axis[axes.length];
                System.arraycopy(axes, 0, movementAxes, 0, axes.length);
                return this;
            }
        }

        @Override
        public MovementConfigEditor configureMotionDetection() {
            return new MovementConfigEditorImpl(MovementType.MOTION);
        }

        @Override
        public MovementConfigEditor configureFreeFallDetection() {
            return new MovementConfigEditorImpl(MovementType.FREE_FALL);
        }

        @Override
        public void enableMovementDetection(final MovementType type) {
            this.movementType= type;
            writeRegister(Mma8452qAccelerometerRegister.MOVEMENT_ENABLE, (byte) 1);
        }

        @Override
        public void disableMovementDetection() {
            writeRegister(Mma8452qAccelerometerRegister.MOVEMENT_ENABLE, (byte) 0);
        }

        @Override
        public OrientationConfigEditor configureOrientationDetection() {
            return new OrientationConfigEditor() {
                private int delay= 150;

                @Override
                public OrientationConfigEditor setDelay(int delay) {
                    this.delay= delay;
                    return this;
                }

                @Override
                public void commit() {
                    orientationDelay= this.delay;
                }
            };
        }

        @Override
        public void enableOrientationDetection() {
            mma8452qOrientationCfg[1]= (byte) 0xc0;
            writeRegister(Mma8452qAccelerometerRegister.ORIENTATION_ENABLE, (byte) 1);
        }
        @Override
        public void disableOrientationDetection() {
            writeRegister(Mma8452qAccelerometerRegister.ORIENTATION_ENABLE, (byte) 0);
            mma8452qOrientationCfg[1]= 0x0;
            writeRegister(Mma8452qAccelerometerRegister.ORIENTATION_CONFIG, mma8452qOrientationCfg);
        }
    }

    private static final float[] BOSCH_HIGH_THRESHOLD_STEPS= {0.00781f, 0.01563f, 0.03125f, 0.0625f},
            BOSCH_HIGH_HYSTERESIS_STEPS= {0.125f, 0.250f, 0.5f, 1f},
            BOSCH_ANY_MOTION_THS_STEPS= {0.00391f, 0.00781f, 0.01563f, 0.03125f},
            BOSCH_NO_MOTION_THS_STEPS= BOSCH_ANY_MOTION_THS_STEPS,
            BOSCH_TAP_THS_STEPS= {0.0625f, 0.125f, 0.250f, 0.5f};

    private Bmi160AccelerometerImpl bmi160AccModule= null;
    private final byte[] bmi160LowHighConfig= new byte[] {0x7, 0x30, (byte) 0x81, 0x0b, (byte) 0xc0};
    private Bmi160Accelerometer.AccRange bmi160AccRange= Bmi160Accelerometer.AccRange.AR_2G;
    private final byte[] bmi160DataSampling= new byte[] {
            (byte) (0x20 | Bmi160Accelerometer.OutputDataRate.ODR_100_HZ.bitMask()),
            Bmi160Accelerometer.AccRange.AR_2G.bitMask()
    }, bmi160TapConfig= new byte[] { 0x4, 0xa };
    private class Bmi160AccelerometerImpl implements Bmi160Accelerometer {
        private static final float BMI160_ORIENT_HYS_G_PER_STEP= 0.0625f, THETA_STEP= (float) (44.8/63.f);

        private Float highHysteresis= null, highThreshold= null, noMotionThs= null, anyMotionThs= null, tapThs= 1.5f;
        private byte[] motionConfig= new byte[] {0x0, 0x14, 0x14, 0x14};

        @Override
        public SamplingConfigEditor configureAxisSampling() {
            return new SamplingConfigEditor() {
                private Byte undersampleSize= null;
                private AccRange newAccRange= null;
                private OutputDataRate newOdr= null;

                @Override
                public SamplingConfigEditor setFullScaleRange(AccRange range) {
                    newAccRange= range;
                    return this;
                }

                @Override
                public SamplingConfigEditor setOutputDataRate(OutputDataRate odr) {
                    newOdr= odr;
                    return this;
                }

                @Override
                public SamplingConfigEditor enableUndersampling(byte size) {
                    undersampleSize= (byte) (Math.log(size) / Math.log(2));
                    return this;
                }

                @Override
                public void commit() {
                    if (newAccRange != null) {
                        bmi160AccRange= newAccRange;
                        bmi160DataSampling[1]&= 0xf0;
                        bmi160DataSampling[1]|= newAccRange.bitMask();
                    }

                    if (newOdr != null) {
                        bmi160DataSampling[0]&= 0xf0;
                        bmi160DataSampling[0]|= newOdr.bitMask();
                    }

                    bmi160DataSampling[0]&= 0xf;
                    if (undersampleSize != null) {
                        bmi160DataSampling[0]|= (undersampleSize << 4) | 0x80;
                    } else {
                        bmi160DataSampling[0]|= 0x20;
                    }
                }
            };
        }

        @Override
        public float setAxisSamplingRange(float range) {
            final float[] values= new float[] { 2.f, 4.f, 8.f, 16.f };
            Log.d("samplingRange","setting it for our board");
            int closest= closestIndex(values, range);

            bmi160AccRange= Bmi160Accelerometer.AccRange.values()[closest];
            bmi160DataSampling[1]&= 0xf0;
            bmi160DataSampling[1]|= bmi160AccRange.bitMask();
            return values[closest];
        }

        @Override
        public float setOutputDataRate(float frequency) {
            OutputDataRate closestOdr=  Bmi160Accelerometer.OutputDataRate.values()[closestIndex(OutputDataRate.frequencies(), frequency)];

            bmi160DataSampling[0]&= 0xf0;
            bmi160DataSampling[0]|= closestOdr.bitMask();

            bmi160DataSampling[0]&= 0xf;
            if (closestOdr.frequency() < 12.5f) {
                bmi160DataSampling[0]|= 0x80;
            } else {
                bmi160DataSampling[0]|= 0x20;
            }
            return closestOdr.frequency();
        }

        @Override
        public void enableAxisSampling() {
            writeRegister(Bmi160AccelerometerRegister.DATA_INTERRUPT_ENABLE, (byte) 0x1, (byte) 0x0);
            Log.d("axisSampling","enabled");
        }

        @Override
        public void disableAxisSampling() {
            writeRegister(Bmi160AccelerometerRegister.DATA_INTERRUPT_ENABLE, (byte) 0x0, (byte) 0x1);
            Log.d("axisSampling","disabled");
        }

        @Override
        public void enableFlatDetection() {
            writeRegister(Bmi160AccelerometerRegister.FLAT_INTERRUPT_ENABLE, (byte) 1, (byte) 0);
        }

        @Override
        public void disableFlatDetection() {
            writeRegister(Bmi160AccelerometerRegister.FLAT_INTERRUPT_ENABLE, (byte) 0, (byte) 1);
        }

        @Override
        public void start() {
            start((byte) 1);
        }

        @Override
        public void startLowPower() {
            start((byte) 2);
        }

        private void start(byte powerMode) {
            if (highHysteresis != null) {
                bmi160LowHighConfig[2]|= ((int) (highHysteresis / BOSCH_HIGH_HYSTERESIS_STEPS[bmi160AccRange.ordinal()]) & 0x3) << 6;
            }

            if (highThreshold != null) {
                bmi160LowHighConfig[4]= (byte) (highThreshold / BOSCH_HIGH_THRESHOLD_STEPS[bmi160AccRange.ordinal()]);
            }

            if (noMotionThs != null) {
                motionConfig[2]= (byte) (noMotionThs / BOSCH_NO_MOTION_THS_STEPS[bmi160AccRange.ordinal()]);
            }

            if (anyMotionThs != null) {
                motionConfig[1]= (byte) (anyMotionThs / BOSCH_ANY_MOTION_THS_STEPS[bmi160AccRange.ordinal()]);
            }

            if (tapThs != null) {
                bmi160TapConfig[1]&= 0xe0;
                bmi160TapConfig[1]|= (byte) Math.min(15, tapThs / BOSCH_TAP_THS_STEPS[bmi160AccRange.ordinal()]);
                Log.d("configForModel tested","configuring model");
            }

            writeRegister(Bmi160AccelerometerRegister.TAP_CONFIG, bmi160TapConfig);
            writeRegister(Bmi160AccelerometerRegister.MOTION_CONFIG, motionConfig);
            writeRegister(Bmi160AccelerometerRegister.DATA_CONFIG, bmi160DataSampling);
            writeRegister(Bmi160AccelerometerRegister.LOW_HIGH_G_CONFIG, bmi160LowHighConfig);
            writeRegister(Bmi160AccelerometerRegister.POWER_MODE, powerMode);
        }

        @Override
        public void stop() {
            writeRegister(Bmi160AccelerometerRegister.POWER_MODE, (byte) 0x0);
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromAxes() {
                    return new RouteBuilder().fromBmi160Axis();
                }

                @Override
                public DataSignal fromXAxis() {
                    return new RouteBuilder().fromBmi160XAxis();
                }

                @Override
                public DataSignal fromYAxis() {
                    return new RouteBuilder().fromBmi160YAxis();
                }

                @Override
                public DataSignal fromZAxis() {
                    return new RouteBuilder().fromBmi160ZAxis();
                }

                @Override
                public DataSignal fromOrientation() {
                    return new RouteBuilder().fromBmi160Orientation();
                }

                @Override
                public DataSignal fromFlat() {
                    return new RouteBuilder().fromBmi160Flat();
                }

                @Override
                public DataSignal fromStepDetection() {
                    return new RouteBuilder().fromBmi160StepDetection();
                }

                @Override
                public DataSignal fromStepCounter(boolean silent) {
                    return new RouteBuilder().fromBmi160StepCounter(silent ? ReadType.SILENT : ReadType.NORMAL);
                }

                @Override
                public DataSignal fromLowHigh() {
                    return new RouteBuilder().fromBmi160LowHigh();
                }

                @Override
                public DataSignal fromMotion() {
                    return new RouteBuilder().fromBmi160Motion();
                }

                @Override
                public DataSignal fromTap() {
                    return new RouteBuilder().fromBmi160Tap();
                }
            };
        }

        public void enableOrientationDetection() {
            writeRegister(Bmi160AccelerometerRegister.ORIENT_INTERRUPT_ENABLE, (byte) 0x1, (byte) 0x0);
        }

        public void disableOrientationDetection() {
            writeRegister(Bmi160AccelerometerRegister.ORIENT_INTERRUPT_ENABLE, (byte) 0x0, (byte) 0x1);
        }

        public OrientationConfigEditor configureOrientationDetection() {
            return new OrientationConfigEditor() {
                private byte intOrient[]= new byte[] {0x18, 0x48};

                @Override
                public OrientationConfigEditor setHysteresis(float hysteresis) {
                    intOrient[0]&= 0xf;
                    intOrient[0]|= (byte) Math.min(0xf, (byte) (hysteresis / BMI160_ORIENT_HYS_G_PER_STEP));
                    return this;
                }

                @Override
                public OrientationConfigEditor setMode(OrientationMode mode) {
                    intOrient[0]&= 0xfc;
                    intOrient[0]|= mode.ordinal();
                    return this;
                }

                @Override
                public void commit() {
                    writeRegister(Bmi160AccelerometerRegister.ORIENT_CONFIG, intOrient);
                }
            };
        }

        public FlatDetectionConfigEditor configureFlatDetection() {
            return new FlatDetectionConfigEditor() {
                private byte intFlat[]= new byte[] {0x08, 0x11};

                @Override
                public FlatDetectionConfigEditor setHoldTime(FlatHoldTime time) {
                    intFlat[1]&= 0xcf;
                    intFlat[1]|= (time.ordinal() << 4);
                    return this;
                }

                @Override
                public FlatDetectionConfigEditor setFlatTheta(float angle) {
                    intFlat[0]&= 0xc0;
                    intFlat[0]|= ((int) (angle / THETA_STEP) & 0x3f);
                    return this;
                }

                @Override
                public void commit() {
                    writeRegister(Bmi160AccelerometerRegister.FLAT_CONFIG, intFlat);
                }
            };
        }

        @Override
        public StepDetectionConfigEditor configureStepDetection() {
            return new StepDetectionConfigEditor() {
                private byte[] stepCnt= new byte[] {0x15, 0x3};

                @Override
                public StepDetectionConfigEditor setSensitivity(StepSensitivity sensitivity) {
                    stepCnt[1]&= 0xf8;
                    switch (sensitivity) {
                        case NORMAL:
                            stepCnt[0]= 0x15;
                            stepCnt[1]|= 0x3;
                            break;
                        case SENSITIVE:
                            stepCnt[0]= 0x2d;
                            break;
                        case ROBUST:
                            stepCnt[0]= 0x1d;
                            stepCnt[1]|= 0x7;
                            break;
                    }
                    return this;
                }

                @Override
                public StepDetectionConfigEditor enableStepCounter() {
                    stepCnt[1]|= 0x08;
                    return this;
                   // Log.d("detecingStepsConfig","stepsconfig");
                }

                @Override
                public void commit() {
                    writeRegister(Bmi160AccelerometerRegister.STEP_DETECTOR_CONFIG, stepCnt);
                }
            };
        }

        @Override
        public void resetStepCounter() {
            writeRegister(Bmi160AccelerometerRegister.STEP_COUNTER_RESET);
        }

        @Override
        public void readStepCounter(boolean silent) {
            readRegister(Bmi160AccelerometerRegister.STEP_COUNTER_DATA, silent);
        }

        @Override
        public void enableStepDetection() {
            writeRegister(Bmi160AccelerometerRegister.STEP_DETECTOR_INTERRUPT_ENABLE, (byte) 1, (byte) 0);
        }

        @Override
        public void disableStepDetection() {
            writeRegister(Bmi160AccelerometerRegister.STEP_DETECTOR_INTERRUPT_ENABLE, (byte) 0, (byte) 1);
        }

        @Override
        public LowHighDetectionConfigEditor configureLowHighDetection() {
            return new LowHighDetectionConfigEditor() {
                private final float DURATION_STEP = 2.5f, LOW_THRESHOLD_STEP= 0.00781f, LOW_HYSTERESIS_STEP= 0.125f;

                private LowGMode lowGMode= null;
                private Integer lowDuration= null, highDuration= null;
                private Float lowThreshold= null, lowHysteresis= null, newHighThreshold= null, newHighHysteresis= null;

                @Override
                public LowHighDetectionConfigEditor setLowDuration(int duration) {
                    lowDuration= duration;
                    return this;
                }

                @Override
                public LowHighDetectionConfigEditor setLowThreshold(float threshold) {
                    lowThreshold= threshold;
                    return this;
                }

                @Override
                public LowHighDetectionConfigEditor setLowHysteresis(float hysteresis) {
                    lowHysteresis= hysteresis;
                    return this;
                }

                @Override
                public LowHighDetectionConfigEditor setLowGMode(LowGMode mode) {
                    lowGMode= mode;
                    return this;
                }

                @Override
                public LowHighDetectionConfigEditor setHighDuration(int duration) {
                    highDuration= duration;
                    return this;
                }

                @Override
                public LowHighDetectionConfigEditor setHighThreshold(float threshold) {
                    newHighThreshold= threshold;
                    return this;
                }

                @Override
                public LowHighDetectionConfigEditor setHighHysteresis(float hysteresis) {
                    newHighHysteresis= hysteresis;
                    return this;
                }

                @Override
                public void commit() {
                    if (lowDuration != null) {
                        bmi160LowHighConfig[0]= (byte) ((lowDuration / DURATION_STEP) - 1);
                    }
                    if (lowThreshold != null) {
                        bmi160LowHighConfig[1]= (byte) (lowThreshold / LOW_THRESHOLD_STEP);
                    }
                    if (newHighHysteresis != null) {
                        highHysteresis= newHighHysteresis;
                    }
                    if (lowGMode != null) {
                        bmi160LowHighConfig[2]&= 0xfb;
                        bmi160LowHighConfig[2]|= (lowGMode.ordinal() << 2);
                    }
                    if (lowHysteresis != null) {
                        bmi160LowHighConfig[2]&= 0xfc;
                        bmi160LowHighConfig[2]|= ((byte) (lowHysteresis / LOW_HYSTERESIS_STEP) & 0x3);
                    }
                    if (highDuration != null) {
                        bmi160LowHighConfig[3]= (byte) ((highDuration / DURATION_STEP) - 1);
                    }
                    if (newHighThreshold != null) {
                        highThreshold= newHighThreshold;
                    }
                }
            };
        }

        @Override
        public void enableLowHighDetection(boolean lowG, boolean highGx, boolean highGy, boolean highGz) {
            byte enableMask= 0;

            if (lowG) {
                enableMask|= 0x8;
            }
            if (highGx) {
                enableMask|= 0x1;
            }
            if (highGy) {
                enableMask|= 0x2;
            }
            if (highGz) {
                enableMask|= 0x4;
            }

            writeRegister(Bmi160AccelerometerRegister.LOW_HIGH_G_INTERRUPT_ENABLE, enableMask, (byte) 0);
        }

        @Override
        public void disableLowHighDetection() {
            writeRegister(Bmi160AccelerometerRegister.LOW_HIGH_G_INTERRUPT_ENABLE, (byte) 0, (byte) 0x1f);
        }

        @Override
        public AnyMotionConfigEditor configureAnyMotionDetection() {
            return new AnyMotionConfigEditor() {
                private Integer duration= null;
                private Float threshold= null;

                @Override
                public AnyMotionConfigEditor setDuration(int duration) {
                    this.duration= duration;
                    return this;
                }

                @Override
                public AnyMotionConfigEditor setThreshold(float threshold) {
                    this.threshold= threshold;
                    return this;
                }

                @Override
                public void commit() {
                    if (duration != null) {
                        motionConfig[0]&= 0xfc;
                        motionConfig[0]|= duration - 1;
                    }

                    if (threshold != null) {
                        anyMotionThs= threshold;
                    }
                }
            };
        }

        @Override
        public void enableMotionDetection(MotionType type) {
            motionConfig[3]&= 0xfc;

            switch (type) {
                case NO_MOTION:
                    motionConfig[3]|= 0x1;
                    break;
                case SLOW_MOTION:
                    motionConfig[3]&= (~0x1);
                    break;
                case SIGNIFICANT_MOTION:
                    motionConfig[3]|= 0x2;
                    break;
                case ANY_MOTION:
                    motionConfig[3]&= (~0x2);
                    break;
            }

            switch (type) {
                case NO_MOTION:
                case SLOW_MOTION:
                    writeRegister(Bmi160AccelerometerRegister.MOTION_INTERRUPT_ENABLE, (byte) 0x38, (byte) 0);
                    break;
                case ANY_MOTION:
                case SIGNIFICANT_MOTION:
                    writeRegister(Bmi160AccelerometerRegister.MOTION_INTERRUPT_ENABLE, (byte) 0x7, (byte) 0);
                    break;
            }
        }

        @Override
        public void disableMotionDetection() {
            writeRegister(Bmi160AccelerometerRegister.MOTION_INTERRUPT_ENABLE, (byte) 0, (byte) 0x3f);
        }

        @Override
        public NoMotionConfigEditor configureNoMotionDetection() {
            return new NoMotionConfigEditor() {
                private Integer duration= null;
                private Float threshold= null;

                @Override
                public NoMotionConfigEditor setDuration(int duration) {
                    this.duration= duration;
                    return this;
                }

                @Override
                public NoMotionConfigEditor setThreshold(float threshold) {
                    this.threshold= threshold;
                    return this;
                }

                @Override
                public void commit() {
                    if (duration != null) {
                        motionConfig[0]&= 0x3;

                        if (duration >= 1280 && duration <= 20480) {
                            motionConfig[0]|= ((byte) (duration / 1280.f - 1)) << 2;
                        } else if (duration >= 25600 && duration <= 102400) {
                            motionConfig[0]|= (((byte) (duration / 5120.f - 5)) << 2) | 0x40;
                        } else if (duration >= 112640 && duration <= 430080) {
                            motionConfig[0]|= (((byte) (duration / 10240.f - 11)) << 2) | 0x80;
                        }
                    }

                    if (threshold != null) {
                        noMotionThs= threshold;
                    }
                }
            };
        }

        @Override
        public SignificantMotionConfigEditor configureSignificantMotionDetection() {
            return new SignificantMotionConfigEditor() {
                private SkipTime newSkipTime= null;
                private ProofTime newProofTime= null;

                @Override
                public SignificantMotionConfigEditor setSkipTime(SkipTime time) {
                    newSkipTime= time;
                    return this;
                }

                @Override
                public SignificantMotionConfigEditor setProofTime(ProofTime time) {
                    newProofTime= time;
                    return this;
                }


                @Override
                public void commit() {
                    if (newSkipTime != null) {
                        motionConfig[3]|= (newSkipTime.ordinal() << 2);
                    }

                    if (newProofTime != null) {
                        motionConfig[3]|= (newProofTime.ordinal() << 4);
                    }
                }
            };
        }

        @Override
        public SlowMotionConfigEditor configureSlowMotionDetection() {
            return new SlowMotionConfigEditor() {
                private Byte count= null;
                private Float threshold= null;

                @Override
                public SlowMotionConfigEditor setCount(byte count) {
                    this.count= count;
                    return this;
                }

                @Override
                public SlowMotionConfigEditor setThreshold(float threshold) {
                    this.threshold= threshold;
                    return this;
                }

                @Override
                public void commit() {
                    if (count != null) {
                        motionConfig[0]&= 0x3;
                        motionConfig[0]|= (count << 2);
                    }
                    if (threshold != null) {
                        noMotionThs= threshold;
                    }
                }
            };
        }

        @Override
        public TapConfigEditor configureTapDetection() {
            return new TapConfigEditor() {
                private TapQuietTime newTapTime= null;
                private TapShockTime newShockTime= null;
                private DoubleTapWindow newWindow= null;
                private Float newThs= null;

                @Override
                public TapConfigEditor setQuietTime(TapQuietTime time) {
                    newTapTime= time;
                    return this;
                }

                @Override
                public TapConfigEditor setShockTime(TapShockTime time) {
                    newShockTime= time;
                    return this;
                }

                @Override
                public TapConfigEditor setDoubleTapWindow(DoubleTapWindow window) {
                    newWindow= window;
                    return this;
                }

                @Override
                public TapConfigEditor setThreshold(float threshold) {
                    newThs= threshold;
                    return this;
                }

                @Override
                public void commit() {
                    if (newTapTime != null) {
                        bmi160TapConfig[0]|= newTapTime.ordinal() << 7;
                    }

                    if (newShockTime != null) {
                        bmi160TapConfig[0]|= newShockTime.ordinal() << 6;
                    }

                    if (newWindow != null) {
                        bmi160TapConfig[0]|= newWindow.ordinal();
                    }

                    if (newThs != null) {
                        tapThs= newThs;
                    }
                }
            };
        }

        @Override
        public void enableTapDetection(TapType ... types) {
            byte mask= 0;
            for(TapType it: types) {
                switch (it) {
                    case SINGLE:
                        mask|= 0x2;
                        break;
                    case DOUBLE:
                        mask|= 0x1;
                        break;
                }
            }

            writeRegister(Bmi160AccelerometerRegister.TAP_INTERRUPT_ENABLE, mask, (byte) 0);
        }
        @Override
        public void disableTapDetection() {
            writeRegister(Bmi160AccelerometerRegister.TAP_INTERRUPT_ENABLE, (byte) 0, (byte) 0x3);
        }
    }

    private Bmi160Gyro.FullScaleRange bmi160GyroRange = Bmi160Gyro.FullScaleRange.FSR_2000;
    private final byte[] bmi160GyroConfig= new byte[] {
            (byte) (0x20 | Bmi160Gyro.OutputDataRate.ODR_100_HZ.bitMask()),
            Bmi160Gyro.FullScaleRange.FSR_2000.bitMask(),
    };
    private class Bmi160GyroImpl implements Bmi160Gyro {
        @Override
        public ConfigEditor configure() {
            return new ConfigEditor() {
                @Override
                public ConfigEditor setFullScaleRange(FullScaleRange range) {
                    bmi160GyroRange = range;
                    bmi160GyroConfig[1] &= 0xf8;
                    bmi160GyroConfig[1] |= range.bitMask();
                    return this;
                }

                @Override
                public ConfigEditor setOutputDataRate(OutputDataRate odr) {
                    bmi160GyroConfig[0] &= 0xf0;
                    bmi160GyroConfig[0] |= odr.bitMask();
                    return this;
                }

                @Override
                public void commit() {
                    writeRegister(Bmi160GyroRegister.CONFIG, bmi160GyroConfig);
                }
            };
        }

        @Override
        public float setOutputDataRate(float frequency) {
            final float[] values= new float[] { 25f, 50f, 100f, 200f, 400f, 800f, 1600f, 3200f };
            int closest= closestIndex(values, frequency);

            bmi160GyroConfig[0] &= 0xf0;
            bmi160GyroConfig[0] |= OutputDataRate.values()[closest].bitMask();
            writeRegister(Bmi160GyroRegister.CONFIG, bmi160GyroConfig);

            return values[closest];
        }

        @Override
        public float setAngularRateRange(float range) {
            final float[] values= new float[] { 125f, 250f, 500f, 1000f, 2000f };
            int closest= values.length - closestIndex(values, range) - 1;

            FullScaleRange bestFsr= FullScaleRange.values()[closest];
            bmi160GyroRange = bestFsr;
            bmi160GyroConfig[1] &= 0xf8;
            bmi160GyroConfig[1] |= bestFsr.bitMask();
            writeRegister(Bmi160GyroRegister.CONFIG, bmi160GyroConfig);

            return values[closest];
        }

        @Override
        public void start() {
            writeRegister(Bmi160GyroRegister.DATA_INTERRUPT_ENABLE, (byte) 1, (byte) 0);
            writeRegister(Bmi160GyroRegister.POWER_MODE, (byte) 1);
        }

        @Override
        public void stop() {
            writeRegister(Bmi160GyroRegister.POWER_MODE, (byte) 0);
            writeRegister(Bmi160GyroRegister.DATA_INTERRUPT_ENABLE, (byte) 0, (byte) 1);
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromAxes() {
                    return new RouteBuilder().fromBmi160Gyro();
                }

                @Override
                public DataSignal fromXAxis() {
                    return new RouteBuilder().fromBmi160GyroXAxis();
                }

                @Override
                public DataSignal fromYAxis() {
                    return new RouteBuilder().fromBmi160GyroYAxis();
                }

                @Override
                public DataSignal fromZAxis() {
                    return new RouteBuilder().fromBmi160GyroZAxis();
                }
            };
        }
    }

    private abstract class SourceImpl implements MultiChannelTemperature.Source {
        private final byte channel;
        private final byte driver;

        public SourceImpl(byte driver, byte channel) {
            this.driver= driver;
            this.channel= channel;
        }

        @Override
        public byte driver() { return driver; }

        @Override
        public byte channel() { return channel; }
    }
    private class NrfDieImpl extends SourceImpl implements MultiChannelTemperature.NrfDie {
        public NrfDieImpl(byte driver, byte channel) {
            super(driver, channel);
        }

        @Override
        public String getName() {
            return "NRF On-Die Sensor";
        }
    }
    private class ExtThermistorImpl extends SourceImpl implements MultiChannelTemperature.ExtThermistor {
        public ExtThermistorImpl(byte driver, byte channel) {
            super(driver, channel);
        }

        @Override
        public void configure(byte analogReadPin, byte pulldownPin, boolean activeHigh) {
            writeRegister(MultiChannelTempRegister.MODE, channel(), analogReadPin, pulldownPin, (byte) (activeHigh ? 1 : 0));
        }

        @Override
        public String getName() {
            return "External Thermistor";
        }
    }
    private class BMP280Impl extends SourceImpl implements MultiChannelTemperature.BMP280 {
        public BMP280Impl(byte driver, byte channel) {
            super(driver, channel);
        }

        @Override
        public String getName() {
            return "BMP280 Sensor";
        }
    }
    private class PresetThermistorImpl extends SourceImpl implements MultiChannelTemperature.PresetThermistor {
        public PresetThermistorImpl(byte driver, byte channel) {
            super(driver, channel);
        }

        @Override
        public String getName() {
            return "On-board Thermistor";
        }
    }
    private final Class[] tempDriverClasses = new Class[] { NrfDieImpl.class, ExtThermistorImpl.class, BMP280Impl.class, PresetThermistorImpl.class};

    private class SingleChannelTemperatureImpl implements SingleChannelTemperature {
        @Override
        public void readTemperature() {
            readTemperature(false);
        }

        @Override
        public void readTemperature(boolean silent) {
            readRegister(TemperatureRegister.VALUE, silent);
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromSensor() {
                    return fromSensor(false);
                }

                @Override
                public DataSignal fromSensor(boolean silent) {
                    return new RouteBuilder().fromSingleChannelTemp(silent ? ReadType.SILENT : ReadType.NORMAL);
                }
            };
        }

        @Override
        public void enableThermistorMode(byte analogReadPin, byte pulldownPin) {
            writeRegister(TemperatureRegister.THERMISTOR_MODE, (byte) 1, analogReadPin, pulldownPin);
        }

        @Override
        public void disableThermistorMode() {
            writeRegister(TemperatureRegister.THERMISTOR_MODE, new byte[] {0, 0, 0});
        }
    }

    private final ArrayList<MultiChannelTemperature.Source> tempSources= new ArrayList<>();
    private class MultiChannelTemperatureImpl implements MultiChannelTemperature {
        @Override
        public List<Source> getSources() {
            return Collections.unmodifiableList(tempSources);
        }

        @Override
        public void readTemperature(Source src) {
            readTemperature(src, false);
        }

        @Override
        public void readTemperature(Source src, boolean silent) {
            readRegister(MultiChannelTempRegister.TEMPERATURE, silent, src.channel());
        }

        @Override
        public void readTemperature() {
            readTemperature(tempSources.get(0));
        }

        @Override
        public void readTemperature(boolean silent) {
            readTemperature(tempSources.get(0), silent);
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromSource(Source src) {
                    return fromSource(src, false);
                }

                @Override
                public DataSignal fromSource(Source src, boolean silent) {
                    return new RouteBuilder().fromMultiChannelTemp(src, silent ? ReadType.SILENT : ReadType.NORMAL);
                }

                @Override
                public DataSignal fromSensor() {
                    return fromSensor(false);
                }

                @Override
                public DataSignal fromSensor(boolean silent) {
                    return new RouteBuilder().fromMultiChannelTemp(tempSources.get(0), silent ? ReadType.SILENT : ReadType.NORMAL);
                }
            };
        }
    }

    private class Ltr329AmbientLightImpl implements Ltr329AmbientLight {
        @Override
        public void start() {
            writeRegister(Ltr329AmbientLightRegister.ENABLE, (byte) 1);
        }

        @Override
        public void stop() {
            writeRegister(Ltr329AmbientLightRegister.ENABLE, (byte) 0);
        }

        @Override
        public ConfigEditor configure() {
            return new ConfigEditor() {
                private Gain ltr329Gain= Gain.LTR329_GAIN_1X;
                private IntegrationTime ltr329Time= IntegrationTime.LTR329_TIME_100MS;
                private MeasurementRate ltr329Rate= MeasurementRate.LTR329_RATE_500MS;

                @Override
                public ConfigEditor setGain(Gain sensorGain) {
                    ltr329Gain= sensorGain;
                    return this;
                }

                @Override
                public ConfigEditor setIntegrationTime(IntegrationTime time) {
                    ltr329Time= time;
                    return this;
                }

                @Override
                public ConfigEditor setMeasurementRate(MeasurementRate rate) {
                    ltr329Rate= rate;
                    return this;
                }

                @Override
                public void commit() {
                    byte alsContr= (byte) (ltr329Gain.mask << 2);
                    byte alsMeasRate= (byte) ((ltr329Time.mask << 3) | ltr329Rate.ordinal());

                    writeRegister(Ltr329AmbientLightRegister.CONFIG, alsContr, alsMeasRate);
                }
            };
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromSensor() {
                    return new RouteBuilder().fromLtr329Output();
                }
            };
        }
    }

    private class Bmp280BarometerImpl implements Bmp280Barometer {
        @Override
        public void enableAltitudeSampling() {

        }

        @Override
        public void disableAltitudeSampling() {

        }

        @Override
        public void start() {
            writeRegister(Bmp280BarometerRegister.CYCLIC, (byte) 1, (byte) 1);
        }

        @Override
        public void stop() {
            writeRegister(Bmp280BarometerRegister.CYCLIC, (byte) 0, (byte) 0);
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromPressure() {
                    return new RouteBuilder().fromBmp280Pressure();
                }

                @Override
                public DataSignal fromAltitude() {
                    return new RouteBuilder().fromBmp280Altitude();
                }
            };
        }

        @Override
        public ConfigEditor configure() {
            return new ConfigEditor() {
                private OversamplingMode samplingMode= OversamplingMode.STANDARD;
                private FilterMode filterMode= FilterMode.OFF;
                private StandbyTime time= StandbyTime.TIME_0_5;
                private byte tempOversampling= 1;

                @Override
                public ConfigEditor setPressureOversampling(OversamplingMode mode) {
                    samplingMode= mode;
                    tempOversampling= (byte) ((mode == OversamplingMode.ULTRA_HIGH) ? 2 : 1);
                    return this;
                }

                @Override
                public ConfigEditor setFilterMode(FilterMode mode) {
                    filterMode= mode;
                    return this;
                }

                @Override
                public ConfigEditor setStandbyTime(StandbyTime time) {
                    this.time= time;
                    return this;
                }

                @Override
                public void commit() {
                    byte first= (byte) ((byte) (samplingMode.ordinal() << 2) | (tempOversampling << 5));
                    byte second= (byte) ((filterMode.ordinal() << 2) | (time.ordinal() << 5));
                    writeRegister(Bmp280BarometerRegister.CONFIG, first, second);
                }
            };
        }
    }

    private class DataProcessorModuleImpl implements DataProcessorModule {
        @Override
        public void readState(String key, boolean silent) {
            if (dataProcessors.containsKey(key) && dataProcessors.get(key) instanceof RouteBuilder.ProcessedDataSignal) {
                readRegister(DataProcessorRegister.STATE, silent, ((RouteBuilder.ProcessedDataSignal) dataProcessors.get(key)).header.id);
            }
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromState(String key, boolean silent) {
                    return new RouteBuilder().fromDataProcessorState(key, silent);
                }
            };
        }
    }

    private class Bmm150MagnetometerImpl implements Bmm150Magnetometer {
        @Override
        public void setPowerPrsest(PowerPreset preset) {
            switch (preset) {
                case LOW_POWER:
                    writeRegister(Bmm150MagnetometerRegister.DATA_REPETITIONS, (byte) 1, (byte) 2);
                    writeRegister(Bmm150MagnetometerRegister.DATA_RATE, (byte) 0);
                    break;
                case REGULAR:
                    writeRegister(Bmm150MagnetometerRegister.DATA_REPETITIONS, (byte) 4, (byte) 14);
                    writeRegister(Bmm150MagnetometerRegister.DATA_RATE, (byte) 0);
                    break;
                case ENHANCED_REGULAR:
                    writeRegister(Bmm150MagnetometerRegister.DATA_REPETITIONS, (byte) 7, (byte) 26);
                    writeRegister(Bmm150MagnetometerRegister.DATA_RATE, (byte) 0);
                    break;
                case HIGH_ACCURACY:
                    writeRegister(Bmm150MagnetometerRegister.DATA_REPETITIONS, (byte) 23, (byte) 82);
                    writeRegister(Bmm150MagnetometerRegister.DATA_RATE, (byte) 5);
                    break;
            }
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromBField() {
                    return new RouteBuilder().fromBmm150BField();
                }
            };
        }

        @Override
        public void disableBFieldSampling() {
            writeRegister(Bmm150MagnetometerRegister.DATA_INTERRUPT_ENABLE, (byte) 0, (byte) 1);
        }

        @Override
        public void enableBFieldSampling() {
            writeRegister(Bmm150MagnetometerRegister.DATA_INTERRUPT_ENABLE, (byte) 1, (byte) 0);
        }

        @Override
        public void start() {
            writeRegister(Bmm150MagnetometerRegister.POWER_MODE, (byte) 1);
        }

        @Override
        public void stop() {
            writeRegister(Bmm150MagnetometerRegister.POWER_MODE, (byte) 0);
        }
    }

    private class Tcs34725ColorDetectorImpl implements Tcs34725ColorDetector {
        @Override
        public void readColorAdc(boolean silent) {
            readRegister(Tcs34725ColorDetectorRegister.COLOR, silent);
        }

        @Override
        public ConfigEditor configure() {
            return new ConfigEditor() {
                private byte aTime= (byte) 0xff;
                private Gain gain= Gain.GAIN_1X;

                @Override
                public ConfigEditor setIntegrationTime(float time) {
                    aTime= (byte) (256.f - time / 2.4f);
                    return this;
                }

                @Override
                public ConfigEditor setGain(Gain gain) {
                    this.gain= gain;
                    return this;
                }

                @Override
                public void commit() {
                    writeRegister(Tcs34725ColorDetectorRegister.MODE, aTime, (byte) gain.ordinal(), (byte) 0);
                }
            };
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromSensor(boolean silent) {
                    return new RouteBuilder().fromTcs34725Color(silent ? ReadType.SILENT : ReadType.NORMAL);
                }
            };
        }
    }

    private class Bme280HumidityImpl implements Bme280Humidity {
        @Override
        public void setOversampling(OversamplingMode mode) {
            writeRegister(Bme280HumidityRegister.MODE, (byte) (mode.ordinal() + 1));
        }

        @Override
        public void readHumidity(boolean silent) {
            readRegister(Bme280HumidityRegister.HUMIDITY, silent);
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromSensor(boolean silent) {
                    return new RouteBuilder().fromBme280Humidity(silent ? ReadType.SILENT : ReadType.NORMAL);
                }
            };
        }
    }

    private class Tsl2671ProximityImpl implements Tsl2671Proximity {
        @Override
        public void readProximity(boolean silent) {
            readRegister(Tsl2671ProximityRegister.PROXIMITY, silent);
        }

        @Override
        public ConfigEditor configure() {
            return new ConfigEditor() {
                private ReceiverDiode diode= ReceiverDiode.CHANNEL_1;
                private TransmitterDrive driveCurrent= TransmitterDrive.CURRENT_25MA;
                private byte nPulses= 1;
                private byte pTime= (byte) 0xff;

                @Override
                public ConfigEditor setIntegrationTime(float time) {
                    pTime= (byte) (256.f - time / 2.72f);
                    return this;
                }

                @Override
                public ConfigEditor setPulseCount(byte nPulses) {
                    this.nPulses= nPulses;
                    return this;
                }

                @Override
                public ConfigEditor setReceiverDiode(ReceiverDiode diode) {
                    this.diode= diode;
                    return this;
                }

                @Override
                public ConfigEditor setTransmitterDriver(TransmitterDrive driveCurrent) {
                    this.driveCurrent= driveCurrent;
                    return this;
                }

                @Override
                public void commit() {
                    byte[] config= new byte[] {pTime, nPulses, (byte) (((diode.ordinal() + 1) << 4) | (driveCurrent.ordinal() << 6))};
                    writeRegister(Tsl2671ProximityRegister.MODE, config);
                }
            };
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromSensor(boolean silent) {
                    return new RouteBuilder().fromTsl2671Proximity(silent ? ReadType.SILENT : ReadType.NORMAL);
                }
            };
        }
    }

    private Bma255AccelerometerImpl bma255Module= null;
    private final byte[] bma255DataSampling= new byte[] {
            Bma255Accelerometer.OutputDataRate.ODR_62_5HZ.bitMask(),
            bmi160AccRange.bitMask()
    };
    private class Bma255AccelerometerImpl implements Bma255Accelerometer {
        private static final float BMA255_ORIENT_HYS_G_PER_STEP= 0.0625f, THETA_STEP= (float) (44.8/63.f);
        private final byte[] motionConfig= {0x0, 0x14, 0x14}, tapConfig= {0x04, 0x0f}, lowHighConfig= {0x09, 0x30, (byte) 0x81, 0x0f, (byte) 0xc0};

        private Float anyMotionThs = null, noMotionThs= null, tapThs= null, highHysteresis= null, highThreshold= null;

        @Override
        public SamplingConfigEditor configureAxisSampling() {
            return new SamplingConfigEditor() {
                @Override
                public SamplingConfigEditor setFullScaleRange(Bmi160Accelerometer.AccRange range) {
                    bmi160AccRange= range;

                    bma255DataSampling[1]&= 0xf0;
                    bma255DataSampling[1]|= range.bitMask();
                    return this;
                }

                @Override
                public SamplingConfigEditor setOutputDataRate(OutputDataRate odr) {
                    bma255DataSampling[0]&= 0xe0;
                    bma255DataSampling[0]|= odr.bitMask();
                    return this;
                }

                @Override
                public void commit() {
                    writeRegister(Bmi160AccelerometerRegister.DATA_CONFIG, bma255DataSampling);
                }
            };
        }

        @Override
        public Bmi160Accelerometer.AnyMotionConfigEditor configureAnyMotionDetection() {
            return new Bmi160Accelerometer.AnyMotionConfigEditor() {
                private Integer duration= null;
                private Float threshold= null;

                @Override
                public Bmi160Accelerometer.AnyMotionConfigEditor setDuration(int duration) {
                    this.duration= duration;
                    return this;
                }

                @Override
                public Bmi160Accelerometer.AnyMotionConfigEditor setThreshold(float threshold) {
                    this.threshold= threshold;
                    return this;
                }

                @Override
                public void commit() {
                    if (duration != null) {
                        motionConfig[0]&= 0xfc;
                        motionConfig[0]|= duration - 1;
                    }

                    if (threshold != null) {
                        anyMotionThs = threshold;
                    }
                }
            };
        }

        @Override
        public Bmi160Accelerometer.NoMotionConfigEditor configureNoMotionDetection() {
            return new Bmi160Accelerometer.NoMotionConfigEditor() {
                private Integer duration= null;
                private Float threshold= null;

                @Override
                public Bmi160Accelerometer.NoMotionConfigEditor setDuration(int duration) {
                    this.duration= duration;
                    return this;
                }

                @Override
                public Bmi160Accelerometer.NoMotionConfigEditor setThreshold(float threshold) {
                    this.threshold= threshold;
                    return this;
                }

                @Override
                public void commit() {
                    if (duration != null) {
                        motionConfig[0]&= 0x3;

                        if (duration >= 1000 && duration <= 16000) {
                            motionConfig[0]|= ((byte) (duration / 1000 - 1000)) << 2;
                        } else if (duration >= 20000 && duration <= 80000) {
                            motionConfig[0]|= (((byte) (duration - 20000) / 4000) << 2) | 0x40;
                        } else if (duration >= 88000 && duration <= 336000) {
                            motionConfig[0]|= (((byte) (duration - 88000) / 8000) << 2) | 0x80;
                        }
                    }

                    if (threshold != null) {
                        noMotionThs= threshold;
                    }
                }
            };
        }

        @Override
        public Bmi160Accelerometer.SlowMotionConfigEditor configureSlowMotionDetection() {
            return new Bmi160Accelerometer.SlowMotionConfigEditor() {
                private Byte count= null;
                private Float threshold= null;

                @Override
                public Bmi160Accelerometer.SlowMotionConfigEditor setCount(byte count) {
                    this.count= count;
                    return this;
                }

                @Override
                public Bmi160Accelerometer.SlowMotionConfigEditor setThreshold(float threshold) {
                    this.threshold= threshold;
                    return this;
                }

                @Override
                public void commit() {
                    if (count != null) {
                        motionConfig[0]&= 0x3;
                        motionConfig[0]|= (count - 1) << 2;
                    }
                    if (threshold != null) {
                        noMotionThs= threshold;
                    }
                }
            };
        }

        @Override
        public void enableMotionDetection(MotionType type) {
            switch (type) {
                case NO_MOTION:
                    writeRegister(Bmi160AccelerometerRegister.MOTION_INTERRUPT_ENABLE, (byte) 0x78, (byte) 0);
                    break;
                case SLOW_MOTION:
                    writeRegister(Bmi160AccelerometerRegister.MOTION_INTERRUPT_ENABLE, (byte) 0x38, (byte) 0);
                    break;
                case ANY_MOTION:
                    writeRegister(Bmi160AccelerometerRegister.MOTION_INTERRUPT_ENABLE, (byte) 0x7, (byte) 0);
                    break;
            }
        }

        @Override
        public void disableMotionDetection() {
            writeRegister(Bmi160AccelerometerRegister.MOTION_INTERRUPT_ENABLE, (byte) 0, (byte) 0x7f);
        }

        @Override
        public Bmi160Accelerometer.TapConfigEditor configureTapDetection() {
            return new Bmi160Accelerometer.TapConfigEditor() {
                private Bmi160Accelerometer.TapQuietTime newTapTime= null;
                private Bmi160Accelerometer.TapShockTime newShockTime= null;
                private Bmi160Accelerometer.DoubleTapWindow newWindow= null;
                private Float newThs= null;

                @Override
                public Bmi160Accelerometer.TapConfigEditor setQuietTime(Bmi160Accelerometer.TapQuietTime time) {
                    newTapTime= time;
                    return this;
                }

                @Override
                public Bmi160Accelerometer.TapConfigEditor setShockTime(Bmi160Accelerometer.TapShockTime time) {
                    newShockTime= time;
                    return this;
                }

                @Override
                public Bmi160Accelerometer.TapConfigEditor setDoubleTapWindow(Bmi160Accelerometer.DoubleTapWindow window) {
                    newWindow= window;
                    return this;
                }

                @Override
                public Bmi160Accelerometer.TapConfigEditor setThreshold(float threshold) {
                    newThs= threshold;
                    return this;
                }

                @Override
                public void commit() {
                    if (newTapTime != null) {
                        tapConfig[0]|= newTapTime.ordinal() << 7;
                    }

                    if (newShockTime != null) {
                        tapConfig[0]|= newShockTime.ordinal() << 6;
                    }

                    if (newWindow != null) {
                        tapConfig[0]|= newWindow.ordinal();
                    }

                    if (newThs != null) {
                        tapThs= newThs;
                    }
                }
            };
        }

        @Override
        public void enableTapDetection(Bmi160Accelerometer.TapType... types) {
            byte mask= 0;
            for(Bmi160Accelerometer.TapType it: types) {
                switch (it) {
                    case SINGLE:
                        mask|= 0x2;
                        break;
                    case DOUBLE:
                        mask|= 0x1;
                        break;
                }
            }

            writeRegister(Bmi160AccelerometerRegister.TAP_INTERRUPT_ENABLE, mask, (byte) 0);
        }

        @Override
        public void disableTapDetection() {
            writeRegister(Bmi160AccelerometerRegister.TAP_INTERRUPT_ENABLE, (byte) 0, (byte) 0x3);
        }

        @Override
        public FlatDetectionConfigEditor configureFlatDetection() {
            return new FlatDetectionConfigEditor() {
                private byte intFlat[]= new byte[] {0x08, 0x11};

                @Override
                public FlatDetectionConfigEditor setHoldTime(FlatHoldTime time) {
                    intFlat[1]&= 0xcf;
                    intFlat[1]|= (time.ordinal() << 4);
                    return this;
                }

                @Override
                public FlatDetectionConfigEditor setFlatTheta(float angle) {
                    intFlat[0]&= 0xc0;
                    intFlat[0]|= ((int) (angle / THETA_STEP) & 0x3f);
                    return this;
                }

                @Override
                public void commit() {
                    writeRegister(Bmi160AccelerometerRegister.FLAT_CONFIG, intFlat);
                }
            };
        }

        @Override
        public void enableFlatDetection() {
            writeRegister(Bmi160AccelerometerRegister.FLAT_INTERRUPT_ENABLE, (byte) 1, (byte) 0);
        }

        @Override
        public void disableFlatDetection() {
            writeRegister(Bmi160AccelerometerRegister.FLAT_INTERRUPT_ENABLE, (byte) 0, (byte) 1);
        }

        @Override
        public Bmi160Accelerometer.LowHighDetectionConfigEditor configureLowHighDetection() {
            return new Bmi160Accelerometer.LowHighDetectionConfigEditor() {
                private final float DURATION_STEP = 2.f, LOW_THRESHOLD_STEP= 0.00781f, LOW_HYSTERESIS_STEP= 0.125f;

                private Bmi160Accelerometer.LowGMode lowGMode= null;
                private Integer lowDuration= null, highDuration= null;
                private Float lowThreshold= null, lowHysteresis= null, newHighThreshold= null, newHighHysteresis= null;

                @Override
                public Bmi160Accelerometer.LowHighDetectionConfigEditor setLowDuration(int duration) {
                    lowDuration= duration;
                    return this;
                }

                @Override
                public Bmi160Accelerometer.LowHighDetectionConfigEditor setLowThreshold(float threshold) {
                    lowThreshold= threshold;
                    return this;
                }

                @Override
                public Bmi160Accelerometer.LowHighDetectionConfigEditor setLowHysteresis(float hysteresis) {
                    lowHysteresis= hysteresis;
                    return this;
                }

                @Override
                public Bmi160Accelerometer.LowHighDetectionConfigEditor setLowGMode(Bmi160Accelerometer.LowGMode mode) {
                    lowGMode= mode;
                    return this;
                }

                @Override
                public Bmi160Accelerometer.LowHighDetectionConfigEditor setHighDuration(int duration) {
                    highDuration= duration;
                    return this;
                }

                @Override
                public Bmi160Accelerometer.LowHighDetectionConfigEditor setHighThreshold(float threshold) {
                    newHighThreshold= threshold;
                    return this;
                }

                @Override
                public Bmi160Accelerometer.LowHighDetectionConfigEditor setHighHysteresis(float hysteresis) {
                    newHighHysteresis= hysteresis;
                    return this;
                }

                @Override
                public void commit() {
                    if (lowDuration != null) {
                        lowHighConfig[0]= (byte) ((lowDuration / DURATION_STEP) - 1);
                    }
                    if (lowThreshold != null) {
                        lowHighConfig[1]= (byte) (lowThreshold / LOW_THRESHOLD_STEP);
                    }
                    if (newHighHysteresis != null) {
                        highHysteresis= newHighHysteresis;
                    }
                    if (lowGMode != null) {
                        lowHighConfig[2]&= 0xfb;
                        lowHighConfig[2]|= (lowGMode.ordinal() << 2);
                    }
                    if (lowHysteresis != null) {
                        bmi160LowHighConfig[2]&= 0xfc;
                        bmi160LowHighConfig[2]|= ((byte) (lowHysteresis / LOW_HYSTERESIS_STEP) & 0x3);
                    }
                    if (highDuration != null) {
                        bmi160LowHighConfig[3]= (byte) ((highDuration / DURATION_STEP) - 1);
                    }
                    if (newHighThreshold != null) {
                        highThreshold= newHighThreshold;
                    }
                }
            };
        }

        @Override
        public void enableLowHighDetection(boolean lowG, boolean highGx, boolean highGy, boolean highGz) {
            byte enableMask= 0;

            if (lowG) {
                enableMask|= 0x8;
            }
            if (highGx) {
                enableMask|= 0x1;
            }
            if (highGy) {
                enableMask|= 0x2;
            }
            if (highGz) {
                enableMask|= 0x4;
            }

            writeRegister(Bmi160AccelerometerRegister.LOW_HIGH_G_INTERRUPT_ENABLE, enableMask, (byte) 0);
        }

        @Override
        public void disableLowHighDetection() {
            writeRegister(Bmi160AccelerometerRegister.LOW_HIGH_G_INTERRUPT_ENABLE, (byte) 0, (byte) 0x1f);
        }

        @Override
        public float setOutputDataRate(float frequency) {
            OutputDataRate closestOdr= OutputDataRate.values()[closestIndex(OutputDataRate.frequencies(), frequency)];

            bma255DataSampling[0]&= 0xe0;
            bma255DataSampling[0]|= closestOdr.bitMask();
            return closestOdr.frequency();
        }


        @Override
        public float setAxisSamplingRange(float range) {
            final float[] values= new float[] { 2.f, 4.f, 8.f, 16.f };
            int closest= closestIndex(values, range);

            bmi160AccRange= Bmi160Accelerometer.AccRange.values()[closest];
            bma255DataSampling[1]&= 0xf0;
            bma255DataSampling[1]|= bmi160AccRange.bitMask();
            return values[closest];
        }

        @Override
        public void enableAxisSampling() {
            writeRegister(Bmi160AccelerometerRegister.DATA_INTERRUPT_ENABLE, (byte) 0x1, (byte) 0x0);
        }

        @Override
        public void disableAxisSampling() {
            writeRegister(Bmi160AccelerometerRegister.DATA_INTERRUPT_ENABLE, (byte) 0x0, (byte) 0x1);
        }

        @Override
        public void enableOrientationDetection() {
            writeRegister(Bmi160AccelerometerRegister.ORIENT_INTERRUPT_ENABLE, (byte) 0x1, (byte) 0x0);
        }

        @Override
        public void disableOrientationDetection() {
            writeRegister(Bmi160AccelerometerRegister.ORIENT_INTERRUPT_ENABLE, (byte) 0x0, (byte) 0x1);
        }

        @Override
        public OrientationConfigEditor configureOrientationDetection() {
            return new OrientationConfigEditor() {
                private byte intOrient[]= new byte[] {0x18, 0x48};

                @Override
                public OrientationConfigEditor setHysteresis(float hysteresis) {
                    intOrient[0]&= 0xf;
                    intOrient[0]|= (byte) Math.min(0xf, (byte) (hysteresis / BMA255_ORIENT_HYS_G_PER_STEP));
                    return this;
                }

                @Override
                public OrientationConfigEditor setMode(Bmi160Accelerometer.OrientationMode mode) {
                    intOrient[0]&= 0xfc;
                    intOrient[0]|= mode.ordinal();
                    return this;
                }

                @Override
                public void commit() {
                    writeRegister(Bmi160AccelerometerRegister.ORIENT_CONFIG, intOrient);
                }
            };
        }


        @Override
        public void start() {
            //private Float anyMotionThs = null, noMotionThs= null, tapThs= null, highHysteresis= null, highThreshold= null;

            if (highHysteresis != null) {
                lowHighConfig[2]|= ((int) (highHysteresis / BOSCH_HIGH_HYSTERESIS_STEPS[bmi160AccRange.ordinal()]) & 0x3) << 6;
            }

            if (highThreshold != null) {
                lowHighConfig[4]= (byte) (highThreshold / BOSCH_HIGH_THRESHOLD_STEPS[bmi160AccRange.ordinal()]);
            }

            if (noMotionThs != null) {
                motionConfig[2]= (byte) (noMotionThs / BOSCH_NO_MOTION_THS_STEPS[bmi160AccRange.ordinal()]);
            }

            if (anyMotionThs != null) {
                motionConfig[1]= (byte) (anyMotionThs / BOSCH_ANY_MOTION_THS_STEPS[bmi160AccRange.ordinal()]);
            }

            if (tapThs != null) {
                tapConfig[1]&= 0xe0;
                tapConfig[1]|= (byte) Math.min(15, tapThs / BOSCH_TAP_THS_STEPS[bmi160AccRange.ordinal()]);
            }

            writeRegister(Bmi160AccelerometerRegister.TAP_CONFIG, tapConfig);
            writeRegister(Bmi160AccelerometerRegister.MOTION_CONFIG, motionConfig);
            writeRegister(Bmi160AccelerometerRegister.LOW_HIGH_G_CONFIG, lowHighConfig);
            writeRegister(Bmi160AccelerometerRegister.DATA_CONFIG, bma255DataSampling);
            writeRegister(Bmi160AccelerometerRegister.POWER_MODE, (byte) 1);
        }

        @Override
        public void stop() {
            writeRegister(Bmi160AccelerometerRegister.POWER_MODE, (byte) 0x0);
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromFlat() {
                    return new RouteBuilder().fromBmi160Flat();
                }

                @Override
                public DataSignal fromLowHigh() {
                    return new RouteBuilder().fromBmi160LowHigh();
                }

                @Override
                public DataSignal fromMotion() {
                    return new RouteBuilder().fromBmi160Motion();
                }

                @Override
                public DataSignal fromTap() {
                    return new RouteBuilder().fromBmi160Tap();
                }

                @Override
                public DataSignal fromAxes() {
                    return new RouteBuilder().fromBmi160Axis();
                }

                @Override
                public DataSignal fromXAxis() {
                    return new RouteBuilder().fromBmi160XAxis();
                }

                @Override
                public DataSignal fromYAxis() {
                    return new RouteBuilder().fromBmi160YAxis();
                }

                @Override
                public DataSignal fromZAxis() {
                    return new RouteBuilder().fromBmi160ZAxis();
                }

                @Override
                public DataSignal fromOrientation() {
                    return new RouteBuilder().fromBmi160Orientation();
                }
            };
        }
    }

    private class Bme280BarometerImpl implements Bme280Barometer {
        private byte altitude= 0;

        @Override
        public ConfigEditor configure() {
            return new ConfigEditor() {
                private Bmp280Barometer.OversamplingMode samplingMode= Bmp280Barometer.OversamplingMode.STANDARD;
                private Bmp280Barometer.FilterMode filterMode= Bmp280Barometer.FilterMode.OFF;
                private StandbyTime time= StandbyTime.TIME_0_5;
                private byte tempOversampling= 1;

                @Override
                public ConfigEditor setPressureOversampling(Bmp280Barometer.OversamplingMode mode) {
                    samplingMode= mode;
                    tempOversampling= (byte) ((mode == Bmp280Barometer.OversamplingMode.ULTRA_HIGH) ? 2 : 1);
                    return this;
                }

                @Override
                public ConfigEditor setFilterMode(Bmp280Barometer.FilterMode mode) {
                    filterMode= mode;
                    return this;
                }

                @Override
                public ConfigEditor setStandbyTime(StandbyTime time) {
                    this.time= time;
                    return this;
                }

                @Override
                public void commit() {
                    byte first= (byte) ((samplingMode.ordinal() << 2) | (tempOversampling << 5));
                    byte second= (byte) ((filterMode.ordinal() << 2) | (time.ordinal() << 5));
                    writeRegister(Bmp280BarometerRegister.CONFIG, first, second);
                }
            };
        }

        @Override
        public void enableAltitudeSampling() {
            altitude= 1;
        }

        @Override
        public void disableAltitudeSampling() {
            altitude= 0;
        }

        @Override
        public SourceSelector routeData() {
            return new SourceSelector() {
                @Override
                public DataSignal fromPressure() {
                    return new RouteBuilder().fromBmp280Pressure();
                }

                @Override
                public DataSignal fromAltitude() {
                    return new RouteBuilder().fromBmp280Altitude();
                }
            };
        }

        @Override
        public void start() {
            writeRegister(Bmp280BarometerRegister.CYCLIC, (byte) 1, altitude);
        }

        @Override
        public void stop() {
            writeRegister(Bmp280BarometerRegister.CYCLIC, (byte) 0, altitude);
        }
    }

    @Override
    public <T extends Module> T getModule(Class<T> moduleClass) throws UnsupportedModuleException {
        if (inMetaBootMode()) {
            throw new UnsupportedModuleException("Cannot interact with modules when board is in MetaBoot mode");
        }

        if (!isConnected()) {
            return null;
        }

        ModuleInfo accelModuleinfo= moduleInfo.get(InfoRegister.ACCELEROMETER.moduleOpcode());
        if (moduleClass.equals(Accelerometer.class)) {
            if (!accelModuleinfo.present()) {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }

            switch(accelModuleinfo.implementation()) {
                case Constant.MMA8452Q_IMPLEMENTATION:
                    if (mma8452qModule == null) {
                        mma8452qModule = new Mma8452qAccelerometerImpl();
                    }
                    return moduleClass.cast(mma8452qModule);
                case Constant.BMI160_IMPLEMENTATION:
                    if (bmi160AccModule == null) {
                        bmi160AccModule= new Bmi160AccelerometerImpl();
                    }
                    return moduleClass.cast(bmi160AccModule);
                case Constant.BMA255_IMPLEMENTATION:
                    if (bma255Module == null) {
                        bma255Module= new Bma255AccelerometerImpl();
                    }
                    return moduleClass.cast(bma255Module);
                default:
                    throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
        }
        if (moduleClass.equals(Mma8452qAccelerometer.class)) {
            if (accelModuleinfo.present() && accelModuleinfo.implementation() == Constant.MMA8452Q_IMPLEMENTATION) {
                if (mma8452qModule == null) {
                    mma8452qModule = new Mma8452qAccelerometerImpl();
                }
                return moduleClass.cast(mma8452qModule);
            } else {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
        }
        if (moduleClass.equals(Bmi160Accelerometer.class)) {
            if (accelModuleinfo.present() && accelModuleinfo.implementation() == Constant.BMI160_IMPLEMENTATION) {
                if (bmi160AccModule == null) {
                    bmi160AccModule= new Bmi160AccelerometerImpl();
                }
                return moduleClass.cast(bmi160AccModule);
            } else {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
        }
        if (moduleClass.equals(Bma255Accelerometer.class)) {
            if (accelModuleinfo.present() && accelModuleinfo.implementation() == Constant.BMA255_IMPLEMENTATION) {
                if (bma255Module == null) {
                    bma255Module= new Bma255AccelerometerImpl();
                }
                return moduleClass.cast(bma255Module);
            } else {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
        }

        ModuleInfo gyroModuleinfo= moduleInfo.get(InfoRegister.GYRO.moduleOpcode());
        if (moduleClass.equals(Gyro.class)) {
            if (!gyroModuleinfo.present()) {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
            switch(gyroModuleinfo.implementation()) {
                case Constant.BMI160_GYRO_IMPLEMENTATION:
                    return moduleClass.cast(new Bmi160GyroImpl());
                default:
                    throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
        }
        if (moduleClass.equals(Bmi160Gyro.class)) {
            if (gyroModuleinfo.present() && gyroModuleinfo.implementation() == Constant.BMI160_GYRO_IMPLEMENTATION) {
                return moduleClass.cast(new Bmi160GyroImpl());
            }
            throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
        }

        if (moduleClass.equals(Bmm150Magnetometer.class)) {
            if (moduleInfo.containsKey(InfoRegister.MAGNETOMETER.moduleOpcode()) && moduleInfo.get(InfoRegister.MAGNETOMETER.moduleOpcode()).present()) {
                return moduleClass.cast(new Bmm150MagnetometerImpl());
            }
            throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
        }

        if (moduleClass.equals(Timer.class)) {
            return moduleClass.cast(new TimerImpl());
        }

        if (moduleClass.equals(Logging.class)) {
            return moduleClass.cast(new LoggingImpl());
        }

        if (moduleClass.equals(Gpio.class)) {
            if (moduleInfo.get(InfoRegister.GPIO.moduleOpcode()).present()) {
                return moduleClass.cast(new GpioImpl());
            }
            throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
        }

        ModuleInfo tempModuleinfo= moduleInfo.get(InfoRegister.TEMPERATURE.moduleOpcode());
        if (moduleClass.equals(Temperature.class)) {
            if (!tempModuleinfo.present()) {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }

            switch(tempModuleinfo.implementation()) {
                case Constant.SINGLE_CHANNEL_TEMP_IMPLEMENTATION:
                    return moduleClass.cast(new SingleChannelTemperatureImpl());
                case Constant.MULTI_CHANNEL_TEMP_IMPLEMENTATION:
                    return moduleClass.cast(new MultiChannelTemperatureImpl());
                default:
                    throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
        }
        if (moduleClass.equals(MultiChannelTemperature.class)) {
            if (!tempModuleinfo.present() || tempModuleinfo.implementation() != Constant.MULTI_CHANNEL_TEMP_IMPLEMENTATION) {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
            return moduleClass.cast(new MultiChannelTemperatureImpl());
        }
        if (moduleClass.equals(SingleChannelTemperature.class)) {
            if (!tempModuleinfo.present() || tempModuleinfo.implementation() != Constant.SINGLE_CHANNEL_TEMP_IMPLEMENTATION) {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
            return moduleClass.cast(new SingleChannelTemperatureImpl());
        }

        ModuleInfo ambientModuleInfo= moduleInfo.get(InfoRegister.AMBIENT_LIGHT.moduleOpcode());
        if (moduleClass.equals(AmbientLight.class)) {
            if (!ambientModuleInfo.present()) {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
            switch(ambientModuleInfo.implementation()) {
                case Constant.LTR329_LIGHT_SENSOR:
                    return moduleClass.cast(new Ltr329AmbientLightImpl());
                default:
                    throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
        }
        if (moduleClass.equals(Ltr329AmbientLight.class)) {
            if (!ambientModuleInfo.present() || ambientModuleInfo.implementation() != Constant.LTR329_LIGHT_SENSOR) {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
            return moduleClass.cast(new Ltr329AmbientLightImpl());
        }

        ModuleInfo barometerModuleInfo= moduleInfo.get(InfoRegister.BAROMETER.moduleOpcode());
        if (moduleClass.equals(Barometer.class)) {
            if (!barometerModuleInfo.present()) {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
            switch(barometerModuleInfo.implementation()) {
                case Constant.BMP280_BAROMETER:
                    return moduleClass.cast(new Bmp280BarometerImpl());
                case Constant.BME280_BAROMETER:
                    return moduleClass.cast(new Bme280BarometerImpl());
                default:
                    throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
        }
        if (moduleClass.equals(Bmp280Barometer.class)) {
            if (!barometerModuleInfo.present() || barometerModuleInfo.implementation() != Constant.BMP280_BAROMETER) {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
            return moduleClass.cast(new Bmp280BarometerImpl());
        }
        if (moduleClass.equals(Bme280Barometer.class)) {
            if (!barometerModuleInfo.present() || barometerModuleInfo.implementation() != Constant.BME280_BAROMETER) {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }
            return moduleClass.cast(new Bme280BarometerImpl());
        }

        if (moduleClass.equals(Led.class)) {
            return moduleClass.cast(new LedImpl());
        }

        if (moduleClass.equals(Switch.class)) {
            if (moduleInfo.get(InfoRegister.SWITCH.moduleOpcode()).present()) {
                return moduleClass.cast(new SwitchImpl());
            }
            throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
        }

        if (moduleClass.equals(Debug.class)) {
            return moduleClass.cast(new DebugImpl());
        }

        ModuleInfo conductanceInfo= moduleInfo.get(InfoRegister.CONDUCTANCE.moduleOpcode());
        if (moduleClass.equals(Conductance.class)) {
            if (!conductanceInfo.present()) {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }

            switch(conductanceInfo.implementation()) {
                case Constant.GSR_IMPLEMENTATION:
                    return moduleClass.cast(new GsrImpl());
                default:
                    return moduleClass.cast(new ConductanceImpl());
            }
        }

        if (moduleClass.equals(Gsr.class)) {
            if (!conductanceInfo.present() || conductanceInfo.implementation() != Constant.GSR_IMPLEMENTATION) {
                throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
            }

            return moduleClass.cast(new GsrImpl());
        }

        if (moduleClass.equals(IBeacon.class)) {
            if (moduleInfo.get(InfoRegister.IBEACON.moduleOpcode()).present()) {
                return moduleClass.cast(new IBeaconImpl());
            }
            throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
        }

        if (moduleClass.equals(Haptic.class)) {
            if (moduleInfo.get(InfoRegister.HAPTIC.moduleOpcode()).present()) {
                return moduleClass.cast(new HapticImpl());
            }
            throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
        }

        if (moduleClass.equals(NeoPixel.class)) {
            if (moduleInfo.get(InfoRegister.NEO_PIXEL.moduleOpcode()).present()) {
                return moduleClass.cast(new NeoPixelImpl());
            }
            throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
        }

        if (moduleClass.equals(Settings.class)) {
            return moduleClass.cast(new SettingsImpl());
        }

        if (moduleClass.equals(I2C.class)) {
            return moduleClass.cast(new I2CImpl());
        }

        if (moduleClass.equals(Macro.class)) {
            return moduleClass.cast(new MacroImpl());
        }

        if (moduleClass.equals(DataProcessorModule.class)) {
            return moduleClass.cast(new DataProcessorModuleImpl());
        }

        if (moduleClass.equals(Tcs34725ColorDetector.class)) {
            if (moduleInfo.containsKey(InfoRegister.COLOR_DETECTOR.moduleOpcode()) && moduleInfo.get(InfoRegister.COLOR_DETECTOR.moduleOpcode()).present()) {
                return moduleClass.cast(new Tcs34725ColorDetectorImpl());
            }
            throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
        }

        if (moduleClass.equals(Bme280Humidity.class)) {
            if (moduleInfo.containsKey(InfoRegister.HUMIDITY.moduleOpcode()) && moduleInfo.get(InfoRegister.HUMIDITY.moduleOpcode()).present()) {
                return moduleClass.cast(new Bme280HumidityImpl());
            }
            throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
        }

        if (moduleClass.equals(Tsl2671Proximity.class)) {
            if (moduleInfo.containsKey(InfoRegister.PROXIMITY.moduleOpcode()) && moduleInfo.get(InfoRegister.PROXIMITY.moduleOpcode()).present()) {
                return moduleClass.cast(new Tsl2671ProximityImpl());
            }
            throw new UnsupportedModuleException(createUnsupportedModuleMsg(moduleClass));
        }

        throw new UnsupportedModuleException("Unrecognized module class: \'" + moduleClass.toString() + "\'");
    }

    private boolean saveCommand= false;
    private EventListener currEventListener;
    private byte[] eventConfig= null;
    private DataSignal.DataToken eventParams= null;
    private byte eventDestOffset= 0;
    private void buildBlePacket(byte[] cmd) {
        if (eventConfig != null) {
            currEventListener.onCommandWrite();
            byte[] eventEntry= new byte[] {EventRegister.ENTRY.moduleOpcode(), EventRegister.ENTRY.opcode(),
                    eventConfig[0], eventConfig[1], eventConfig[2],
                    cmd[0], cmd[1], (byte) (cmd.length - 2)};
            if (eventParams != null) {
                byte[] tempEntry= new byte[eventEntry.length + 2];
                System.arraycopy(eventEntry, 0, tempEntry, 0, eventEntry.length);
                tempEntry[eventEntry.length]= (byte) (0x01 | ((eventParams.length() << 1) & 0xff) | ((eventParams.offset() << 4) & 0xff));
                tempEntry[eventEntry.length + 1]= eventDestOffset;
                eventEntry= tempEntry;
            }
            currEventListener.queueCommand(eventEntry);
            //conn.sendCommand(false, eventEntry);

            byte[] eventParameters= new byte[cmd.length];
            System.arraycopy(cmd, 2, eventParameters, 2, cmd.length - 2);
            eventParameters[0]= EventRegister.CMD_PARAMETERS.moduleOpcode();
            eventParameters[1]= EventRegister.CMD_PARAMETERS.opcode();
            currEventListener.queueCommand(eventParameters);
            //conn.sendCommand(false, eventParameters);
        } else {
            conn.sendCommand(false, cmd);
            if (saveCommand) {
                commands.add(cmd);
            }
        }
    }

    private void writeRegister(Register register, byte ... parameters) {
        buildBlePacket(Registers.buildWriteCommand(register, parameters));
    }
    private void readRegister(Register register, boolean silent, byte ... parameters) {
        if (silent) {
            buildBlePacket(Registers.buildSilentReadCommand(register, parameters));
        } else {
            buildBlePacket(Registers.buildReadCommand(register, parameters));
        }
    }
}