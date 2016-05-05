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

/**
 * Controller for interacting with the LTR329 light sensor.  This sensor is only available on MetaWear
 * R+Gyro boards.
 * @author Eric Tsai
 */
public interface Ltr329AmbientLight extends AmbientLight {
    /**
     * Controls the range and resolution of illuminance values
     * @author Eric Tsai
     */
    enum Gain {
        /** Illuminance range between [1, 64k] lux (default) */
        LTR329_GAIN_1X((byte) 0),
        /** Illuminance range between [0.5, 32k] lux */
        LTR329_GAIN_2X((byte) 1),
        /** Illuminance range between [0.25, 16k] lux */
        LTR329_GAIN_4X((byte) 2),
        /** Illuminance range between [0.125, 8k] lux */
        LTR329_GAIN_8X((byte) 3),
        /** Illuminance range between [0.02, 1.3k] lux */
        LTR329_GAIN_48X((byte) 6),
        /** Illuminance range between [0.01, 600] lux */
        LTR329_GAIN_96X((byte) 7);

        /** Bitmask representing the setting */
        public final byte mask;
        Gain(byte mask) {
            this.mask= mask;
        }
    }

    /**
     * Measurement time for each cycle
     * @author Eric Tsai
     */
    enum IntegrationTime {
        LTR329_TIME_50MS((byte) 1),
        /** Default setting */
        LTR329_TIME_100MS((byte) 0),
        LTR329_TIME_150MS((byte) 4),
        LTR329_TIME_200MS((byte) 2),
        LTR329_TIME_250MS((byte) 5),
        LTR329_TIME_300MS((byte) 6),
        LTR329_TIME_350MS((byte) 7),
        LTR329_TIME_400MS((byte) 3);

        public final byte mask;
        IntegrationTime(byte mask) {
            this.mask= mask;
        }
    }

    /**
     * How frequently to update the illuminance data.
     * @author Eric Tsai
     */
    enum MeasurementRate {
        LTR329_RATE_50MS,
        LTR329_RATE_100MS,
        LTR329_RATE_200MS,
        /** Default setting */
        LTR329_RATE_500MS,
        LTR329_RATE_1000MS,
        LTR329_RATE_2000MS
    }

    /**
     * Interface for configuring the LTR329 light sensor.  Default settings will be used for parameters
     * that are not configured upon commit.
     * @author Eric Tsai
     */
    interface ConfigEditor {
        /**
         * Sets the gain setting
         * @param sensorGain    New gain setting to use
         * @return Calling object
         */
        ConfigEditor setGain(Gain sensorGain);

        /**
         * Sets the integration time
         * @param time    New integration time to use
         * @return Calling object
         */
        ConfigEditor setIntegrationTime(IntegrationTime time);

        /**
         * Sets the measurement rate
         * @param rate    New measurement rate to use, chosen rate must be greater than or equal to the
         *                integration time
         * @return Calling object
         */
        ConfigEditor setMeasurementRate(MeasurementRate rate);

        /**
             * Writes the new settings to the board
         */
        void commit();
    }

    /**
     * Configures the sensor
     * @return Editor object to set various parameters
     */
    ConfigEditor configure();
}
