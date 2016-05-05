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

package com.mbientlab.metawear.impl.characteristic;

/**
 * Created by etsai on 7/25/2015.
 */
public enum InfoRegister implements Register {
    SWITCH {
        @Override
        public byte moduleOpcode() { return 0x1; }
    },
    LED {
        @Override
        public byte moduleOpcode() { return 0x2; }
    },
    ACCELEROMETER {
        @Override
        public byte moduleOpcode() { return 0x3; }
    },
    TEMPERATURE {
        @Override
        public byte moduleOpcode() { return 0x4; }
    },
    GPIO {
        @Override
        public byte moduleOpcode() { return 0x5; }
    },
    NEO_PIXEL {
        @Override
        public byte moduleOpcode() { return 0x6; }
    },
    IBEACON {
        @Override
        public byte moduleOpcode() { return 0x7; }
    },
    HAPTIC {
        @Override
        public byte moduleOpcode() { return 0x8; }
    },
    DATA_PROCESSOR {
        @Override
        public byte moduleOpcode() { return 0x9; }
    },
    EVENT {
        @Override
        public byte moduleOpcode() { return 0xa; }
    },
    LOGGING {
        @Override
        public byte moduleOpcode() { return 0xb; }
    },
    TIMER {
        @Override
        public byte moduleOpcode() { return 0xc; }
    },
    I2C {
        @Override
        public byte moduleOpcode() { return 0xd; }
    },
    MACRO {
        @Override
        public byte moduleOpcode() { return 0xf; }
    },
    CONDUCTANCE {
        @Override
        public byte moduleOpcode() { return 0x10; }
    },
    SETTINGS {
        @Override
        public byte moduleOpcode() { return 0x11; }
    },
    BAROMETER {
        @Override
        public byte moduleOpcode() { return 0x12; }
    },
    GYRO {
        @Override
        public byte moduleOpcode() { return 0x13; }
    },
    AMBIENT_LIGHT {
        @Override
        public byte moduleOpcode() { return 0x14; }
    },
    MAGNETOMETER {
        @Override
        public byte moduleOpcode() { return 0x15; }
    },
    HUMIDITY {
        @Override
        public byte moduleOpcode() { return 0x16; }
    },
    COLOR_DETECTOR {
        @Override
        public byte moduleOpcode() { return 0x17; }
    },
    PROXIMITY {
        @Override
        public byte moduleOpcode() { return 0x18; }
    },
    DEBUG {
        @Override
        public byte moduleOpcode() { return (byte) 0xfe; }
    };


    @Override
    public byte opcode() { return 0; }
}
