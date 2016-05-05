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

package com.mbientlab.metawear.processor;

import com.mbientlab.metawear.DataSignal;
import com.mbientlab.metawear.module.DataProcessor;

import java.util.Map;

/**
 * Configuration for the averaging data processor
 * @author Eric Tsai
 */
public class Average implements DataSignal.ProcessorConfig {
    public static final String SCHEME_NAME= "average";
    public static final String FIELD_SAMPLE_SIZE= "sampleSize";

    /**
     * Representation of the average processor's internal state.  The state can only be reset.
     * @author Eric Tsai
     */
    public static class State implements DataProcessor.State { }

    public final byte sampleSize;

    /**
     * Constructs an average config object from a URI string
     * @param query    String-String map containing the fields from the URI string
     */
    public Average(Map<String, String> query) {
        if (!query.containsKey(FIELD_SAMPLE_SIZE)) {
            throw new RuntimeException("Missing required field in URI: " + FIELD_SAMPLE_SIZE);
        }
        sampleSize= Byte.valueOf(query.get(FIELD_SAMPLE_SIZE));
    }

    /**
     * Constructs a config object for the average transformer
     * @param sampleSize How many data samples to use for computing a running average.  Recommended
     *                   to be a power of 2 for faster computation.
     */
    public Average(byte sampleSize) {
        this.sampleSize= sampleSize;
    }
}
