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

package com.mbientlab.metawear;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.mbientlab.metawear.impl.*;
import com.mbientlab.metawear.impl.characteristic.InfoRegister;
import com.mbientlab.metawear.impl.characteristic.LoggingRegister;
import com.mbientlab.metawear.impl.characteristic.MacroRegister;
import com.mbientlab.metawear.impl.characteristic.Register;
import com.mbientlab.metawear.impl.characteristic.Registers;
import com.mbientlab.metawear.impl.dfu.DfuService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by etsai on 6/15/2015.
 */
public class MetaWearBleService extends Service {
    private final static String DEVICE_STATE_KEY= "com.mbientlab.metawear.MetaWearBleService.DEVICE_STATE_KEY", FIRMWARE_BUILD= "vanilla";
    private final static UUID CHARACTERISTIC_CONFIG= UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            METAWEAR_SERVICE= UUID.fromString("326A9000-85CB-9195-D9DD-464CFBBAE75A"),
            METAWEAR_COMMAND= UUID.fromString("326A9001-85CB-9195-D9DD-464CFBBAE75A"),
            METAWEAR_NOTIFY= UUID.fromString("326A9006-85CB-9195-D9DD-464CFBBAE75A"),
            DFU_SERVICE = UUID.fromString("00001530-1212-efde-1523-785feabcd123");

    private final static long FUTURE_CHECKER_PERIOD= 1000L;

    private enum GattActionKey {
        NONE,
        DESCRIPTOR_WRITE,
        CHAR_READ,
        CHAR_WRITE,
        RSSI_READ
    }

    private class DummyModuleInfo implements ModuleInfo {
        private final byte moduleId, implementation;
        private final boolean present;
        public DummyModuleInfo(Register infoRegister, byte implementation, boolean present) {
            this.moduleId= infoRegister.moduleOpcode();
            this.implementation= implementation;
            this.present= present;
        }

        @Override
        public byte id() {
            return moduleId;
        }

        @Override
        public byte implementation() {
            return implementation;
        }

        @Override
        public byte revision() {
            return 0;
        }

        @Override
        public byte[] extra() {
            return null;
        }

        @Override
        public boolean present() {
            return present;
        }
    }
    private class ModuleInfoImpl implements ModuleInfo {
        private final static String JSON_FIELD_IMPLEMENTATION= "implementation", JSON_FIELD_REVISION= "revision",
                JSON_FIELD_EXTRA= "extra", JSON_FIELD_PRESENT= "present";
        private final byte id, implementation, revision;
        private final byte[] extra;
        private final boolean present;

        public ModuleInfoImpl(byte[] response) {
            id= response[0];

            if (response.length > 2) {
                present = true;

                implementation = response[2];
                revision = response[3];
            } else {
                present= false;
                implementation= -1;
                revision= -1;
            }

            if (response.length <= 4) {
                extra= null;
            } else {
                extra= new byte[response.length - 4];
                System.arraycopy(response, 4, extra, 0, extra.length);
            }
        }

        public ModuleInfoImpl(byte id, JSONObject state) throws JSONException {
            this.id= id;

            implementation= (byte) state.getInt(JSON_FIELD_IMPLEMENTATION);
            revision= (byte) state.getInt(JSON_FIELD_REVISION);

            JSONArray extraBytes= state.optJSONArray(JSON_FIELD_EXTRA);
            if (extraBytes == null) {
                extra= null;
            } else {
                extra= new byte[extraBytes.length()];
                for(byte j= 0; j < extraBytes.length(); j++) {
                    extra[j]= (byte) extraBytes.getInt(j);
                }
            }

            present= state.getBoolean(JSON_FIELD_PRESENT);
        }

        @Override
        public byte id() { return id; }

        @Override
        public byte implementation() { return implementation; }

        @Override
        public byte revision() { return revision; }

        @Override
        public byte[] extra() {
            return extra;
        }

        @Override
        public boolean present() { return present; }

        public JSONObject serialize() throws JSONException {
            JSONObject state= new JSONObject();

            state.put(JSON_FIELD_IMPLEMENTATION, implementation);
            state.put(JSON_FIELD_REVISION, revision);

            if (extra != null) {
                JSONArray extraState= new JSONArray();
                for(byte it: extra) {
                    extraState.put(it);
                }
                state.put(JSON_FIELD_EXTRA, extraState);
            }

            state.put(JSON_FIELD_PRESENT, present);

            return state;
        }
    }

    private final int GATT_FORCE_EXEC_DELAY= 1000;
    private final Runnable gattForceExec= new Runnable() {
        @Override
        public void run() {
            handlerThreadPool.removeCallbacks(this);
            gattManager.setExpectedGattKey(GattActionKey.NONE);
            gattManager.executeNext(GattActionKey.NONE);
        }
    };
    private class GattConnectionState {
        public final HashMap<DevInfoCharacteristic, String> devInfoValues= new HashMap<>();
        public final HashMap<Byte, ModuleInfoImpl> moduleInfo= new HashMap<>();
        public final AndroidBleConnection androidConn;
        public final AtomicInteger nDescriptors= new AtomicInteger(0);
        public final AtomicBoolean isConnected= new AtomicBoolean(false), isReady= new AtomicBoolean(false);
        public final ConcurrentLinkedQueue<AsyncOperationAndroidImpl<Integer>> rssiResult= new ConcurrentLinkedQueue<>();
        public final ConcurrentLinkedQueue<Register> infoRegisters= new ConcurrentLinkedQueue<>();
        private final Runnable connectionTimeoutHandler = new Runnable() {
            @Override
            public void run() {
                isConnected.set(false);
                isReady.set(false);
                if (androidConn.gatt != null) {
                    androidConn.gatt.close();
                }

                if (connectionHandler != null) {
                    connectionHandler.failure(-1, new TimeoutException("Board connection timed out"));
                }
            }
        };
        public final ExecutorService responseHandlerQueue= Executors.newSingleThreadExecutor(), taskExecutionQueue= Executors.newSingleThreadExecutor();

        public MetaWearBoard.ConnectionStateHandler connectionHandler;
        public boolean isMetaBoot= false;

        private final BluetoothDevice device;

        public GattConnectionState(AndroidBleConnection androidConn, BluetoothDevice device) {
            this.androidConn= androidConn;
            this.device= device;
        }

        public boolean deviceInfoReady() {
            return devInfoValues.keySet().size() == DevInfoCharacteristic.values().length;
        }

        public void discoverModuleInfo() {
            infoRegisters.clear();
            infoRegisters.addAll(Arrays.asList(InfoRegister.values()));
        }

        public void checkConnectionReady() {
            if (deviceInfoReady()) {
                if (isMetaBoot) {
                    isReady.set(true);
                    queueRunnable(new Runnable() {
                        @Override
                        public void run() {
                            if (connectionHandler != null) {
                                connectionHandler.connected();
                            }
                        }
                    });
                } else if (infoRegisters.isEmpty()) {
                    try {
                        String cachedState= deviceStates.getString(device.getAddress(), "");

                        if (!cachedState.isEmpty()) {
                            JSONObject jsonCachedState = new JSONObject(cachedState);
                            JSONObject persistentState = jsonCachedState.optJSONObject("persistent_state");

                            if (persistentState != null) {
                                mwBoards.get(device).deserializePersistentState(persistentState);
                            }
                        }
                    } catch (JSONException e) {
                        Log.w("MetaWear", "Cannot deserialize persistent state", e);
                    }

                    isReady.set(true);
                    androidConn.queueCommand(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, Registers.buildReadCommand(LoggingRegister.TIME), true);
                } else {
                    androidConn.queueCommand(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, Registers.buildReadCommand(infoRegisters.poll()), true);
                }
            }
        }

        public MetaWearBoard.DeviceInformation buildDeviceInfo() {
            return new MetaWearBoard.DeviceInformation() {
                @Override public String manufacturer() { return devInfoValues.get(DevInfoCharacteristic.MANUFACTURER_NAME); }
                @Override public String serialNumber() { return devInfoValues.get(DevInfoCharacteristic.SERIAL_NUMBER); }
                @Override public String firmwareRevision() { return devInfoValues.get(DevInfoCharacteristic.FIRMWARE_VERSION); }
                @Override public String hardwareRevision() { return devInfoValues.get(DevInfoCharacteristic.HARDWARE_VERSION); }
                @Override public String modelNumber() { return devInfoValues.get(DevInfoCharacteristic.MODEL_NUMBER); }
                @Override public String toString() {
                    return String.format("{manufacturer: %s, serialNumber: %s, firmwareRevision: %s, hardwareRevision: %s, modelNumber: %s}",
                            manufacturer(), serialNumber(), firmwareRevision(), hardwareRevision(), modelNumber());
                }
            };
        }

        public void reset() {
            isMetaBoot= false;
            nDescriptors.set(0);
            isReady.set(false);
            isConnected.set(false);
            devInfoValues.clear();
        }
    }
    private interface Action {
        boolean execute();
    }
    private class GattActionManager {
        private ConcurrentLinkedQueue<Action> actions= new ConcurrentLinkedQueue<>();
        private AtomicBoolean isExecActions= new AtomicBoolean();
        private GattActionKey gattKey= GattActionKey.NONE;

        public void setExpectedGattKey(GattActionKey newKey) {
            gattKey= newKey;
        }

        public void updateExecActionsState() {
            handlerThreadPool.removeCallbacks(gattForceExec);
            isExecActions.set(!actions.isEmpty());
        }
        public void queueAction(Action newAction) {
            actions.add(newAction);
        }
        public void executeNext(GattActionKey key) {
            if (!actions.isEmpty()) {
                if (key == gattKey || !isExecActions.get()) {
                    isExecActions.set(true);
                    boolean lastResult= false;
                    try {
                        while(!actions.isEmpty() && !(lastResult = actions.poll().execute())) { }

                        if (lastResult) {
                            handlerThreadPool.postDelayed(gattForceExec, GATT_FORCE_EXEC_DELAY);
                        }
                    } catch (NullPointerException ex) {
                        lastResult= false;
                    }

                    if (!lastResult && actions.isEmpty()) {
                        isExecActions.set(false);
                        gattKey= GattActionKey.NONE;
                    }
                }
            } else {
                isExecActions.set(false);
                gattKey= GattActionKey.NONE;
            }
        }
    }

    private class AsyncOperationAndroidImpl<T> implements AsyncOperation<T> {
        private final ConcurrentLinkedQueue<CompletionHandler<T>> handlers= new ConcurrentLinkedQueue<>();
        private final AtomicBoolean completed= new AtomicBoolean(false);
        private T result;
        private Throwable exception;

        private Runnable throwTimeoutException;

        @Override
        public void onComplete(CompletionHandler<T> handler) {
            handlers.add(handler);

            if (completed.get()) {
                executeHandlers();
            }
        }

        public void setResult(T result, Throwable exception) {
            if (!completed.get()) {
                handlerThreadPool.removeCallbacks(throwTimeoutException);
                this.result= result;
                this.exception= exception;

                completed.set(true);
                executeHandlers();
            }
        }

        private void executeHandlers() {
            queueRunnable(new Runnable() {
                @Override
                public void run() {
                    while(!handlers.isEmpty()) {
                        CompletionHandler<T> next= handlers.poll();

                        if (next != null) {
                            if (exception != null) {
                                next.failure(exception);
                            } else {
                                try {
                                    next.success(result);
                                } catch (Exception e) {
                                    next.failure(e);
                                }
                            }
                        }
                    }
                }
            });
        }

        @Override
        public boolean isComplete() {
            return completed.get();
        }

        @Override
        public T result() throws ExecutionException, InterruptedException {
            if (!completed.get()) {
                throw new InterruptedException("Task not yet completed");
            }

            if (exception != null) {
                throw new ExecutionException("Received exception when executing task", exception);
            }
            return result;
        }

        public boolean setTimeout(Runnable r, long timeout) {
            if (completed.get()) {
                return false;
            }

            if (throwTimeoutException != null) {
                handlerThreadPool.removeCallbacks(throwTimeoutException);
            }

            throwTimeoutException= r;
            handlerThreadPool.postDelayed(throwTimeoutException, timeout);
            return true;
        }
    }

    private class AndroidBleConnection implements Connection {
        private BluetoothGatt gatt;

        public void setBluetoothGatt(BluetoothGatt newGatt) {
            this.gatt= newGatt;
        }

        public void queueCommand(final int writeType, final byte[] command) {
            queueCommand(writeType, command, false);
        }

        private void queueCommand(final int writeType, final byte[] command, final boolean override) {
            gattManager.queueAction(new Action() {
                @Override
                public boolean execute() {
                    if (gatt != null && (override || gattConnectionStates.get(gatt.getDevice()).isReady.get())) {
                        gattManager.setExpectedGattKey(GattActionKey.CHAR_WRITE);
                        mwBoards.get(gatt.getDevice()).wroteCommand(command);

                        BluetoothGattService service = gatt.getService(METAWEAR_SERVICE);
                        BluetoothGattCharacteristic mwCmdChar = service.getCharacteristic(METAWEAR_COMMAND);
                        mwCmdChar.setWriteType(writeType);
                        mwCmdChar.setValue(command);
                        gatt.writeCharacteristic(mwCmdChar);
                        return true;
                    }
                    return false;
                }
            });
            gattManager.executeNext(GattActionKey.NONE);
        }

        @Override
        public <T> void setResultReady(AsyncOperation<T> async, T result, Throwable error) {
            ((AsyncOperationAndroidImpl<T>) async).setResult(result, error);
        }

        @Override
        public void setOpTimeout(AsyncOperation<?> async, Runnable r, long timeout) {
            ((AsyncOperationAndroidImpl<?>) async).setTimeout(r, timeout);
        }

        @Override
        public <T> AsyncOperation<T> createAsyncOperation() {
            return new AsyncOperationAndroidImpl<>();
        }

        @Override
        public void executeTask(Runnable r, long delay) {
            handlerThreadPool.postDelayed(r, delay);
        }

        @Override
        public void executeTask(Runnable r) {
            if (useHandler) {
                handlerThreadPool.post(r);
            } else {
                backgroundFutures.add(gattConnectionStates.get(gatt.getDevice()).taskExecutionQueue.submit(r));
            }

        }

        @Override
        public void sendCommand(boolean writeMacro, byte[] command) {
            if (writeMacro) {
                byte[] macroBytes;

                if (command.length >= DefaultMetaWearBoard.MW_COMMAND_LENGTH) {
                    final byte PARTIAL_LENGTH= 2;
                    macroBytes= new byte[PARTIAL_LENGTH + 2];
                    macroBytes[0]= MacroRegister.ADD_PARTIAL.moduleOpcode();
                    macroBytes[1]= MacroRegister.ADD_PARTIAL.opcode();
                    System.arraycopy(command, 0, macroBytes, 2, PARTIAL_LENGTH);
                    queueCommand(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, macroBytes);

                    macroBytes= new byte[command.length - PARTIAL_LENGTH + 2];
                    macroBytes[0]= MacroRegister.ADD_COMMAND.moduleOpcode();
                    macroBytes[1]= MacroRegister.ADD_COMMAND.opcode();
                    System.arraycopy(command, PARTIAL_LENGTH, macroBytes, 2, macroBytes.length - 2);
                    queueCommand(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, macroBytes);
                } else {
                    macroBytes= new byte[command.length + 2];
                    macroBytes[0]= MacroRegister.ADD_COMMAND.moduleOpcode();
                    macroBytes[1]= MacroRegister.ADD_COMMAND.opcode();
                    System.arraycopy(command, 0, macroBytes, 2, command.length);
                    queueCommand(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, macroBytes);
                }
            } else {
                queueCommand(command[0] == InfoRegister.MACRO.moduleOpcode() ?
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT :
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                        command);
            }
        }
    }

    private class DefaultMetaWearBoardAndroidImpl extends DefaultMetaWearBoard {
        private static final long READ_ATTR_TIMEOUT= 5000L, DEFAULT_CONNECTION_TIME= 15000L;

        private final ConcurrentLinkedQueue<AsyncOperationAndroidImpl<Byte>> batteryResult= new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<AsyncOperationAndroidImpl<Boolean>> checkFirmwareResult= new ConcurrentLinkedQueue<>();
        private final BluetoothDevice btDevice;

        private final AtomicBoolean uploadingFirmware= new AtomicBoolean();
        private AsyncOperationAndroidImpl<Void> dfuOperation= null;
        private BluetoothGatt gatt;
        private DfuService dfuService;

        public DefaultMetaWearBoardAndroidImpl(BluetoothDevice btDevice, AndroidBleConnection androidConn) {
            super(androidConn);
            this.btDevice= btDevice;
            gattConnectionStates.put(btDevice, new GattConnectionState(androidConn, btDevice));
        }

        public void onCharacteristicRead(BluetoothGattCharacteristic characteristic, int status) {
            if (characteristic.getService().getUuid().equals(BattLevelCharacteristic.serviceUuid())) {
                if (status != 0) {
                    batteryResult.poll().setResult(null, new RuntimeException(String.format(Locale.US, "Error reading battery level characteristic (%d)", status)));
                } else {
                    batteryResult.poll().setResult(characteristic.getValue()[0], null);
                }
            }
        }

        @Override
        public String getMacAddress() {
            return btDevice.getAddress();
        }

        @Override
        public AsyncOperation<DeviceInformation> readDeviceInformation() {
            final AsyncOperationAndroidImpl<DeviceInformation> result= new AsyncOperationAndroidImpl<>();
            final GattConnectionState state= gattConnectionStates.get(btDevice);

            if (!isConnected()) {
                result.setResult(null, new RuntimeException("Bluetooth LE connection not established"));
            } else {
                result.setResult(state.buildDeviceInfo(), null);
            }

            return result;
        }

        @Override
        public AsyncOperation<Integer> readRssi() {
            final GattConnectionState state= gattConnectionStates.get(btDevice);
            final AsyncOperationAndroidImpl<Integer> result= new AsyncOperationAndroidImpl<>();

            state.rssiResult.add(result);
            result.setTimeout(new Runnable() {
                @Override
                public void run() {
                    state.rssiResult.remove(result);
                    result.setResult(null, new TimeoutException(String.format("RSSI read timed out after %dms", READ_ATTR_TIMEOUT)));
                }
            }, READ_ATTR_TIMEOUT);
            gattManager.queueAction(new Action() {
                @Override
                public boolean execute() {
                    if (gatt != null && state.isReady.get()) {
                        gattManager.setExpectedGattKey(GattActionKey.RSSI_READ);
                        gatt.readRemoteRssi();
                        return true;
                    }
                    gattConnectionStates.get(btDevice).rssiResult.poll().setResult(null, new RuntimeException("Bluetooth LE connection not established"));
                    return false;
                }
            });
            gattManager.executeNext(GattActionKey.NONE);
            return result;
        }

        @Override
        public AsyncOperation<Byte> readBatteryLevel() {
            final AsyncOperationAndroidImpl<Byte> result= new AsyncOperationAndroidImpl<>();

            batteryResult.add(result);
            result.setTimeout(new Runnable() {
                @Override
                public void run() {
                    batteryResult.remove(result);
                    result.setResult(null, new TimeoutException(String.format("RSSI read timed out after %dms", READ_ATTR_TIMEOUT)));
                }
            }, READ_ATTR_TIMEOUT);
            gattManager.queueAction(new Action() {
                @Override
                public boolean execute() {
                    if (gatt != null && gattConnectionStates.get(gatt.getDevice()).isReady.get()) {
                        gattManager.setExpectedGattKey(GattActionKey.CHAR_READ);
                        BluetoothGattService service = gatt.getService(BattLevelCharacteristic.serviceUuid());

                        if (service != null) {
                            BluetoothGattCharacteristic batLevelChar = service.getCharacteristic(BattLevelCharacteristic.LEVEL.uuid());
                            gatt.readCharacteristic(batLevelChar);
                            return true;
                        }

                        batteryResult.poll().setResult(null, new RuntimeException("Battery service not found"));
                        return false;
                    }
                    batteryResult.poll().setResult(null, new RuntimeException("Bluetooth LE connection not established"));
                    return false;
                }
            });
            gattManager.executeNext(GattActionKey.NONE);
            return result;
        }

        public void close() {
            GattConnectionState state= gattConnectionStates.get(btDevice);
            state.isConnected.set(false);
            state.isReady.set(false);

            if (gatt != null) {
                try {
                    final Method refresh = gatt.getClass().getMethod("refresh");
                    refresh.invoke(gatt);
                } catch (final Exception e) {
                    Log.e("MetaWear", "Error refreshing gatt cache", e);
                } finally {
                    gatt.close();
                    gatt= null;
                }
            }
        }

        @Override
        public void setConnectionStateHandler(ConnectionStateHandler handler) {
            gattConnectionStates.get(btDevice).connectionHandler= handler;
        }

        @Override
        public void connect() {
            if (!isConnected()) {
                close();

                GattConnectionState state= gattConnectionStates.get(btDevice);
                state.reset();

                handlerThreadPool.postDelayed(state.connectionTimeoutHandler, DEFAULT_CONNECTION_TIME);
                gatt= btDevice.connectGatt(MetaWearBleService.this, false, gattCallback);
                gattConnectionStates.get(btDevice).androidConn.setBluetoothGatt(gatt);
            }
        }

        @Override
        public void disconnect() {
            if (gatt != null) {
                gatt.disconnect();
            }
        }

        @Override
        public boolean isConnected() {
            return gattConnectionStates.get(btDevice).isReady.get();
        }

        @Override
        public boolean inMetaBootMode() {
            return gattConnectionStates.get(btDevice).isMetaBoot;
        }

        private AsyncOperation<Void> updateFirmwareInner(Object firmwareHex, final DfuProgressHandler handler) {
            if (uploadingFirmware.get()) {
                AsyncOperationAndroidImpl<Void> result= new AsyncOperationAndroidImpl<>();
                result.setResult(null, new RuntimeException("Firmware update already in progress"));
                return result;
            }

            if (!isConnected()) {
                AsyncOperationAndroidImpl<Void> result= new AsyncOperationAndroidImpl<>();
                result.setResult(null, new RuntimeException("Connect to tbe board before updating the firmware"));
                return result;
            }

            dfuOperation= new AsyncOperationAndroidImpl<>();

            try {
                final DfuProgressHandler handlerWrapper= new DfuProgressHandler() {
                    @Override
                    public void reachedCheckpoint(final State dfuState) {
                        queueRunnable(new Runnable() {
                            @Override
                            public void run() {
                                handler.reachedCheckpoint(dfuState);
                            }
                        });
                    }

                    @Override
                    public void receivedUploadProgress(final int progress) {
                        queueRunnable(new Runnable() {
                            @Override
                            public void run() {
                                handler.receivedUploadProgress(progress);
                            }
                        });
                    }
                };

                final Object firmware;
                if (firmwareHex == null) {
                    firmware = new URL(String.format("http://releases.mbientlab.com/metawear/%s/%s/latest/firmware.hex",
                            gattConnectionStates.get(btDevice).devInfoValues.get(DevInfoCharacteristic.MODEL_NUMBER),
                            FIRMWARE_BUILD));
                } else {
                    firmware = firmwareHex;
                }

                close();

                backgroundFutures.add(backgroundThreadPool.submit(new Runnable() {
                    @Override
                    public void run() {
                        uploadingFirmware.set(true);
                        try {
                            dfuService = new DfuService(btDevice, firmware, MetaWearBleService.this, inMetaBootMode(), handlerWrapper);
                            Future<?> dfuFuture = backgroundThreadPool.submit(dfuService);
                            dfuFuture.get();
                            dfuOperation.setResult(null, null);
                        } catch (Exception e) {
                            dfuOperation.setResult(null, e.getCause());
                        } finally {
                            uploadingFirmware.set(false);
                        }
                    }
                }));
            } catch (MalformedURLException e) {
                dfuOperation.setResult(null, e);
            }

            return dfuOperation;
        }

        @Override
        public AsyncOperation<Void> updateFirmware(File firmwareHexPath, DfuProgressHandler handler) {
            return updateFirmwareInner(firmwareHexPath, handler);
        }

        @Override
        public AsyncOperation<Void> updateFirmware(final DfuProgressHandler handler) {
            return updateFirmwareInner(null, handler);
        }

        @Override
        public AsyncOperation<Void> updateFirmware(InputStream firmwareStream, DfuProgressHandler handler) {
            return updateFirmwareInner(firmwareStream, handler);
        }

        @Override
        public AsyncOperation<Boolean> checkForFirmwareUpdate() {
            AsyncOperationAndroidImpl<Boolean> result= new AsyncOperationAndroidImpl<>();
            if (!isConnected()) {
                result.setResult(null, new RuntimeException("You must be connected to the board before checking for firmware updates"));
            } else {
                checkFirmwareResult.add(result);
                backgroundFutures.add(backgroundThreadPool.submit(new Runnable() {
                    @Override
                    public void run() {
                        HttpURLConnection urlConn = null;

                        try {
                            URL infoJson = new URL("http://releases.mbientlab.com/metawear/info.json");
                            urlConn = (HttpURLConnection) infoJson.openConnection();
                            InputStream ins = urlConn.getInputStream();

                            StringBuilder response = new StringBuilder();
                            byte data[] = new byte[1024];
                            int count;
                            while ((count = ins.read(data)) != -1) {
                                response.append(new String(data, 0, count));
                            }

                            JSONObject releaseInfo = new JSONObject(response.toString());
                            JSONObject builds = releaseInfo.getJSONObject(gattConnectionStates.get(btDevice).devInfoValues.get(DevInfoCharacteristic.MODEL_NUMBER));
                            JSONObject availableVersions = builds.getJSONObject(FIRMWARE_BUILD);

                            Iterator<String> versionKeys = availableVersions.keys();
                            TreeSet<Version> versions = new TreeSet<>();
                            while (versionKeys.hasNext()) {
                                versions.add(new Version(versionKeys.next()));
                            }

                            Version currentVersion = new Version(gattConnectionStates.get(btDevice).devInfoValues.get(DevInfoCharacteristic.FIRMWARE_VERSION));
                            Iterator<Version> it = versions.descendingIterator();
                            if (it.hasNext()) {
                                checkFirmwareResult.poll().setResult(it.next().compareTo(currentVersion) > 0, null);
                            } else {
                                checkFirmwareResult.poll().setResult(null, new RuntimeException("No information about released firmware versions available"));
                            }
                        } catch (Exception e) {
                            checkFirmwareResult.poll().setResult(null, e);
                        } finally {
                            if (urlConn != null) {
                                urlConn.disconnect();
                            }
                        }
                    }
                }));
            }
            return result;
        }

        @Override
        public void abortFirmwareUpdate() {
            if (uploadingFirmware.get()) {
                dfuService.abort();
            }
        }
    }

    private final GattActionManager gattManager= new GattActionManager();
    private final HashMap<BluetoothDevice, DefaultMetaWearBoardAndroidImpl> mwBoards= new HashMap<>();
    private final HashMap<BluetoothDevice, GattConnectionState> gattConnectionStates= new HashMap<>();
    private SharedPreferences deviceStates;

    private final BluetoothGattCallback gattCallback= new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            final GattConnectionState state= gattConnectionStates.get(gatt.getDevice());

            switch (newState) {
                case BluetoothProfile.STATE_CONNECTED:
                    gattConnectionStates.get(gatt.getDevice()).isConnected.set(status == 0);

                    if (status != 0) {
                        handlerThreadPool.removeCallbacks(state.connectionTimeoutHandler);
                        mwBoards.get(gatt.getDevice()).close();

                        final int paramStatus= status;
                        queueRunnable(new Runnable() {
                            @Override
                            public void run() {
                                if (state.connectionHandler != null) {
                                    state.connectionHandler.failure(paramStatus, new RuntimeException("Error connecting to gatt server"));
                                }
                            }
                        });
                    } else {
                        gatt.discoverServices();
                    }
                    break;
                case BluetoothProfile.STATE_DISCONNECTED:
                    mwBoards.get(gatt.getDevice()).close();
                    handlerThreadPool.removeCallbacks(state.connectionTimeoutHandler);


                    if (status != 0) {
                        final int paramStatus= status;
                        queueRunnable(new Runnable() {
                            @Override
                            public void run() {
                                if (state.connectionHandler != null) {
                                    state.connectionHandler.failure(paramStatus, new RuntimeException("onConnectionStateChanged reported non-zero status: " + paramStatus));
                                }
                            }
                        });
                    } else {
                        queueRunnable(new Runnable() {
                            @Override
                            public void run() {
                                if (state.connectionHandler != null) {
                                    state.connectionHandler.disconnected();
                                }
                            }
                        });
                    }
                    break;
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            if (status != 0) {
                final GattConnectionState state= gattConnectionStates.get(gatt.getDevice());
                mwBoards.get(gatt.getDevice()).close();

                state.isConnected.set(false);
                handlerThreadPool.removeCallbacks(state.connectionTimeoutHandler);

                final int paramStatus= status;
                queueRunnable(new Runnable() {
                    @Override
                    public void run() {
                        if (state.connectionHandler != null) {
                            state.connectionHandler.failure(paramStatus, new RuntimeException("Error discovering Bluetooth services"));
                        }
                    }
                });
            } else {
                for (BluetoothGattService service : gatt.getServices()) {
                    if (service.getUuid().equals(DFU_SERVICE)) {
                        gattConnectionStates.get(gatt.getDevice()).isMetaBoot= true;
                    }
                    for (final BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                        int charProps = characteristic.getProperties();
                        if ((charProps & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            gattConnectionStates.get(gatt.getDevice()).nDescriptors.incrementAndGet();

                            gattManager.queueAction(new Action() {
                                @Override
                                public boolean execute() {
                                    if (gatt != null && gattConnectionStates.get(gatt.getDevice()).isConnected.get()) {
                                        gatt.setCharacteristicNotification(characteristic, true);

                                        gattManager.setExpectedGattKey(GattActionKey.DESCRIPTOR_WRITE);
                                        Log.d("MetawearBLEService","Gatt set Key");
                                        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CHARACTERISTIC_CONFIG);
                                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                        gatt.writeDescriptor(descriptor);

                                        return true;
                                    }
                                    return false;
                                }
                            });
                        }
                    }
                }
            }

            gattManager.executeNext(GattActionKey.NONE);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            gattManager.updateExecActionsState();
            gattManager.executeNext(GattActionKey.CHAR_WRITE);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic.getUuid().equals(METAWEAR_NOTIFY)) {
                final GattConnectionState state= gattConnectionStates.get(gatt.getDevice());
                final byte[] response= characteristic.getValue();
                byte registerId= (byte) (response[1] & 0x7f);
                Log.d("charChange","gatt connection states responses");

                // All info registers are id = 0
                if (InfoRegister.LOGGING.opcode() == registerId) {
                    ModuleInfoImpl info = new ModuleInfoImpl(response);


                    state.moduleInfo.put(info.id(), info);
                    mwBoards.get(gatt.getDevice()).receivedModuleInfo(info);

                    state.checkConnectionReady();
                } else if (response[0] == LoggingRegister.TIME.moduleOpcode() && registerId == LoggingRegister.TIME.opcode()) {
                    handlerThreadPool.removeCallbacks(state.connectionTimeoutHandler);

                    DefaultMetaWearBoardAndroidImpl board= mwBoards.get(gatt.getDevice());
                    board.receivedLoggingTick(response);

                    try {
                        SharedPreferences.Editor editor = deviceStates.edit();
                        JSONObject newCachedState = new JSONObject();
                        for(DevInfoCharacteristic it: DevInfoCharacteristic.values()) {
                            newCachedState.put(it.key(), state.devInfoValues.get(it));
                        }

                        JSONObject newModuleState = new JSONObject();
                        for (ModuleInfoImpl it : state.moduleInfo.values()) {
                            newModuleState.put(JSONObject.numberToString(it.id()), it.serialize());
                        }
                        newCachedState.put("module_info", newModuleState);
                        newCachedState.put("persistent_state", board.serializePersistentState());

                        editor.putString(state.device.getAddress(), newCachedState.toString());
                        editor.apply();
                    } catch (JSONException e) {
                        Log.w("MetaWear", "Could not cache device information", e);
                    }

                    queueRunnable(new Runnable() {
                        @Override
                        public void run() {
                            if (state.connectionHandler != null) {
                                state.connectionHandler.connected();
                            }
                        }
                    });
                } else {
                    final BluetoothDevice paramDevice= gatt.getDevice();
                    backgroundFutures.add(state.responseHandlerQueue.submit(new Runnable() {
                        @Override
                        public void run() {
                            mwBoards.get(paramDevice).receivedResponse(response);
                        }
                    }));
                }
            }
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            gattManager.updateExecActionsState();

            final GattConnectionState state= gattConnectionStates.get(gatt.getDevice());
            if (status != 0) {
                mwBoards.get(gatt.getDevice()).close();

                state.isConnected.set(false);
                gattManager.executeNext(GattActionKey.DESCRIPTOR_WRITE);
                handlerThreadPool.removeCallbacks(state.connectionTimeoutHandler);

                final int paramStatus= status;
                queueRunnable(new Runnable() {
                    @Override
                    public void run() {
                        if (state.connectionHandler != null) {
                            state.connectionHandler.failure(paramStatus, new RuntimeException("Error writing descriptors"));
                        }
                    }
                });
            } else {
                gattManager.executeNext(GattActionKey.DESCRIPTOR_WRITE);
                int newCount= state.nDescriptors.decrementAndGet();
                if (newCount == 0) {
                    String cachedState= deviceStates.getString(gatt.getDevice().getAddress(), "");

                    if (cachedState.isEmpty()) {
                        readDeviceInformation(gatt, state);
                    } else {
                        try {
                            JSONObject jsonCachedState = new JSONObject(cachedState);
                            state.devInfoValues.put(DevInfoCharacteristic.MODEL_NUMBER, jsonCachedState.getString(DevInfoCharacteristic.MODEL_NUMBER.key()));
                            state.devInfoValues.put(DevInfoCharacteristic.HARDWARE_VERSION, jsonCachedState.getString(DevInfoCharacteristic.HARDWARE_VERSION.key()));
                            state.devInfoValues.put(DevInfoCharacteristic.MANUFACTURER_NAME, jsonCachedState.getString(DevInfoCharacteristic.MANUFACTURER_NAME.key()));
                            state.devInfoValues.put(DevInfoCharacteristic.SERIAL_NUMBER, jsonCachedState.getString(DevInfoCharacteristic.SERIAL_NUMBER.key()));

                            gattManager.queueAction(new Action() {
                                @Override
                                public boolean execute() {
                                    BluetoothGattService service = gatt.getService(DevInfoCharacteristic.serviceUuid());

                                    if (service != null) {
                                        BluetoothGattCharacteristic devInfoChar = service.getCharacteristic(DevInfoCharacteristic.FIRMWARE_VERSION.uuid());

                                        if (devInfoChar != null) {
                                            gattManager.setExpectedGattKey(GattActionKey.CHAR_READ);
                                            gatt.readCharacteristic(devInfoChar);
                                            return true;
                                        }
                                    } else {
                                        state.devInfoValues.put(DevInfoCharacteristic.FIRMWARE_VERSION, null);
                                    }

                                    handlerThreadPool.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            state.checkConnectionReady();
                                        }
                                    });
                                    return false;
                                }
                            });
                        } catch (JSONException e) {
                            readDeviceInformation(gatt, state);
                        }
                    }

                    gattManager.executeNext(GattActionKey.NONE);
                }
            }
        }

        private void readDeviceInformation(final BluetoothGatt gatt, final GattConnectionState state) {
            for (final DevInfoCharacteristic it : DevInfoCharacteristic.values()) {
                gattManager.queueAction(new Action() {
                    @Override
                    public boolean execute() {
                        BluetoothGattService service = gatt.getService(DevInfoCharacteristic.serviceUuid());

                        if (service != null) {
                            BluetoothGattCharacteristic devInfoChar = service.getCharacteristic(it.uuid());

                            if (devInfoChar != null) {
                                gattManager.setExpectedGattKey(GattActionKey.CHAR_READ);
                                gatt.readCharacteristic(devInfoChar);
                                return true;
                            }

                            // Older firmware (< 1.0.4) did not have model number but those firmware
                            // versions were only on MetaWear R boards
                            state.devInfoValues.put(it, it == DevInfoCharacteristic.MODEL_NUMBER ?
                                    Constant.METAWEAR_R_MODULE :
                                    null);
                        } else {
                            // MetaWear R boards did not have the Device Information service in bootloader
                            // mode.  If the service is not there, the board must be a MetaWear R.
                            state.devInfoValues.put(it, it == DevInfoCharacteristic.MODEL_NUMBER ?
                                    Constant.METAWEAR_R_MODULE :
                                    null);
                        }

                        state.checkConnectionReady();
                        return false;
                    }
                });
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            gattManager.updateExecActionsState();
            gattManager.executeNext(GattActionKey.RSSI_READ);

            if (status != 0) {
                gattConnectionStates.get(gatt.getDevice()).rssiResult.poll().setResult(null,
                        new RuntimeException(String.format(Locale.US, "Error reading RSSI value (%d)", status)));
            } else {
                gattConnectionStates.get(gatt.getDevice()).rssiResult.poll().setResult(rssi, null);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            gattManager.updateExecActionsState();
            gattManager.executeNext(GattActionKey.CHAR_READ);

            final GattConnectionState state= gattConnectionStates.get(gatt.getDevice());
            DefaultMetaWearBoardAndroidImpl board= mwBoards.get(gatt.getDevice());

            if (characteristic.getService().getUuid().equals(DevInfoCharacteristic.serviceUuid())) {
                if (status != 0) {
                    mwBoards.get(gatt.getDevice()).close();
                    handlerThreadPool.removeCallbacks(state.connectionTimeoutHandler);

                    final int paramStatus= status;
                    queueRunnable(new Runnable() {
                        @Override
                        public void run() {
                            if (state.connectionHandler != null) {
                                state.connectionHandler.failure(paramStatus, new RuntimeException("Error reading device information"));
                            }
                        }
                    });
                } else {
                    String charStringValue= new String(characteristic.getValue());
                    state.devInfoValues.put(DevInfoCharacteristic.uuidToDevInfoCharacteristic(characteristic.getUuid()),
                            charStringValue);

                    if (!state.isMetaBoot && characteristic.getUuid().equals(DevInfoCharacteristic.FIRMWARE_VERSION.uuid())) {
                        Version deviceFirmware= new Version(charStringValue);
                        board.setFirmwareVersion(deviceFirmware);

                        if (deviceFirmware.compareTo(Constant.SERVICE_DISCOVERY_MIN_FIRMWARE) < 0) {
                            board.receivedModuleInfo(new DummyModuleInfo(InfoRegister.AMBIENT_LIGHT, (byte) -1, false));
                            board.receivedModuleInfo(new DummyModuleInfo(InfoRegister.BAROMETER, (byte) -1, false));
                            board.receivedModuleInfo(new DummyModuleInfo(InfoRegister.CONDUCTANCE, (byte) -1, false));
                            board.receivedModuleInfo(new DummyModuleInfo(InfoRegister.TEMPERATURE, Constant.SINGLE_CHANNEL_TEMP_IMPLEMENTATION, true));
                            board.receivedModuleInfo(new DummyModuleInfo(InfoRegister.ACCELEROMETER, Constant.MMA8452Q_IMPLEMENTATION, true));
                            board.receivedModuleInfo(new DummyModuleInfo(InfoRegister.GYRO, (byte) -1, false));

                            board.receivedModuleInfo(new DummyModuleInfo(InfoRegister.IBEACON, (byte) 0, true));
                            board.receivedModuleInfo(new DummyModuleInfo(InfoRegister.HAPTIC, (byte) 0, true));
                            board.receivedModuleInfo(new DummyModuleInfo(InfoRegister.NEO_PIXEL, (byte) 0, true));
                            board.receivedModuleInfo(new DummyModuleInfo(InfoRegister.SWITCH, (byte) 0, true));
                        } else {
                            String cachedState= deviceStates.getString(gatt.getDevice().getAddress(), "");
                            if (cachedState.isEmpty()) {
                                state.discoverModuleInfo();
                            } else {
                                try {
                                    JSONObject jsonCachedState= new JSONObject(cachedState);
                                    String cachedFirmware= jsonCachedState.getString(DevInfoCharacteristic.FIRMWARE_VERSION.key());

                                    if (!cachedFirmware.equals(charStringValue)) {
                                        state.discoverModuleInfo();
                                    } else {
                                        JSONObject cachedModuleInfo= jsonCachedState.getJSONObject("module_info");
                                        Iterator<String> keys= cachedModuleInfo.keys();

                                        while(keys.hasNext()) {
                                            String next= keys.next();
                                            ModuleInfoImpl nextInfo= new ModuleInfoImpl(Byte.valueOf(next), cachedModuleInfo.getJSONObject(next));

                                            state.moduleInfo.put(Byte.valueOf(next), nextInfo);
                                            board.receivedModuleInfo(nextInfo);
                                        }
                                    }
                                } catch (JSONException e) {
                                    state.discoverModuleInfo();
                                }
                            }

                        }
                    }

                    state.checkConnectionReady();
                }
            } else {
                board.onCharacteristicRead(characteristic, status);
            }
        }
    };

    private boolean useHandler= false;
    private final Handler handlerThreadPool= new Handler();
    private final ExecutorService backgroundThreadPool= Executors.newCachedThreadPool();
    private final ConcurrentLinkedQueue<Future<?>> backgroundFutures= new ConcurrentLinkedQueue<>();
    private final TimerTask futureCheckerTask= new TimerTask() {
        @Override
        public void run() {
            ConcurrentLinkedQueue<Future<?>> notYetCompleted= new ConcurrentLinkedQueue<>();
            while (!backgroundFutures.isEmpty()) {
                Future<?> next= backgroundFutures.poll();
                if (next.isDone()) {
                    try {
                        next.get();
                    } catch (Exception e) {
                        Log.e("MetaWear", "Background task reported an error", e);
                    }
                } else {
                    notYetCompleted.add(next);
                }
            }

            backgroundFutures.addAll(notYetCompleted);
        }
    };
    private Timer futureTimer = new Timer();
    /**
     * Provides methods for interacting with the service
     * @author Eric Tsai
     */
    public class LocalBinder extends Binder {
        /**
         * Instantiates a MetaWearBoard class
         * @param btDevice    BluetoothDevice object corresponding to the target MetaWear board
         * @return MetaWearBoard object
         */
        public MetaWearBoard getMetaWearBoard(BluetoothDevice btDevice) {
            if (!mwBoards.containsKey(btDevice)) {
                mwBoards.put(btDevice, new DefaultMetaWearBoardAndroidImpl(btDevice, new AndroidBleConnection()));
            }
            return mwBoards.get(btDevice);
        }

        /**
         * Removes cached data for a MetaWear board
         * @param btDevice    BluetoothDevice object corresponding to the target MetaWear board
         */
        public void clearCachedState(BluetoothDevice btDevice) {
            SharedPreferences.Editor editor= deviceStates.edit();
            editor.remove(btDevice.getAddress());
            editor.apply();
        }

        /**
         * Executes asynchronous tasks on the UI thread
         */
        public void executeOnUiThread() {
            useHandler= true;
            futureTimer.cancel();
        }

        /**
         * Executes asynchronous tasks on a background thread.  This is the default behaviour, to minimize
         * activity on the UI thread.
         */
        public void executeOnBackgroundThread() {
            useHandler= false;
            futureTimer = new Timer();
            futureTimer.scheduleAtFixedRate(futureCheckerTask, 0, FUTURE_CHECKER_PERIOD);
        }
    }

    private void queueRunnable(Runnable r) {
        if (useHandler) {
            handlerThreadPool.post(r);
        } else {
            backgroundFutures.add(backgroundThreadPool.submit(r));
        }
    }

    @Override
    public void onDestroy() {
        for(Map.Entry<BluetoothDevice, DefaultMetaWearBoardAndroidImpl> it: mwBoards.entrySet()) {
            it.getValue().close();
        }
        super.onDestroy();
    }

    private final IBinder mBinder= new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate () {
        super.onCreate();

        futureTimer.scheduleAtFixedRate(futureCheckerTask, 0, FUTURE_CHECKER_PERIOD);
        deviceStates= getSharedPreferences(DEVICE_STATE_KEY, MODE_PRIVATE);
    }
}
