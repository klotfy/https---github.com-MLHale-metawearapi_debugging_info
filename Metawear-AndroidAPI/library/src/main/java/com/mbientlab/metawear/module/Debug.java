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

package com.mbientlab.metawear.module;

import com.mbientlab.metawear.MetaWearBoard;

/**
 * Debug functions for advanced use
 * @author Eric Tsai
 */
public interface Debug extends MetaWearBoard.Module {
    /**
     * Restart the board
     */
    void resetDevice();

    /**
     * Restart the board in bootloader mode
     */
    void jumpToBootloader();

    /**
     * Restart the board after performing garbage collection.  This method should be
     * used in lieu of {@link #resetDevice()} if a user wishes to restart the board
     * after erasing macros or log entries.
     * @see Logging#clearEntries()
     * @see Macro#eraseMacros()
     */
    void resetAfterGarbageCollect();

    /**
     * Have the board terminate the connection, as opposed to {@link MetaWearBoard#disconnect()} where
     * the mobile device terminates the connection.  On devices running Lollipop or later, this will call
     * the {@link com.mbientlab.metawear.MetaWearBoard.ConnectionStateHandler#failure(int, Throwable) ConnectionStateHandler.failure}
     * function with a status of 19 rather than
     * {@link com.mbientlab.metawear.MetaWearBoard.ConnectionStateHandler#disconnected() ConnectionStateHandler.disconnected}
     */
    void disconnect();
}
