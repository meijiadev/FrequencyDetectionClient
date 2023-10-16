package com.example.frequencydetectionclient.utils;

import com.example.frequencydetectionclient.bean.SamplePacket;
import com.example.frequencydetectionclient.iq.IQConverter;

/**
 * <h1>RF Analyzer - unsigned 8-bit IQ Converter</h1>
 * <p>
 * Module:      Unsigned8BitIQConverter.java
 * Description:该类实现了将IQ源(8位无符号)的原始输入数据转换为SamplePackets的方法。它还具有同时进行转换和下混的方法。
 *
 * @author Dennis Mantz
 * <p>
 * Copyright (C) 2014 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 * <p>
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 * <p>
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
public class Unsigned8BitIQConverter extends IQConverter {

    public Unsigned8BitIQConverter() {
        super();
    }

    @Override
    protected void generateLookupTable() {
        /**
         * The rtl_sdr delivers samples in the following format:
         * The bytes are interleaved, 8-bit, unsigned IQ samples (in-phase
         *  component first, followed by the quadrature component):
         *
         *  [--------- first sample ----------]   [-------- second sample --------]
         *         I                  Q                  I                Q ...
         *  receivedBytes[0]   receivedBytes[1]   receivedBytes[2]       ...
         */

        lookupTable = new float[256];
        for (int i = 0; i < 256; i++)
            lookupTable[i] = (i - 127.4f) / 128.0f;
    }

    @Override
    protected void generateMixerLookupTable(int mixFrequency) {
        // 如果混合频率太低，只需增加采样率(采样频谱是周期性的):
        if (mixFrequency == 0 || (sampleRate / Math.abs(mixFrequency) > MAX_COSINE_LENGTH))
            mixFrequency += sampleRate;

        // 仅在null或无效时生成lookupTable:
        if (cosineRealLookupTable == null || mixFrequency != cosineFrequency) {
            cosineFrequency = mixFrequency;
            int bestLength = calcOptimalCosineLength();
            cosineRealLookupTable = new float[bestLength][256];
            cosineImagLookupTable = new float[bestLength][256];
            float cosineAtT;
            float sineAtT;
            for (int t = 0; t < bestLength; t++) {
                cosineAtT = (float) Math.cos(2 * Math.PI * cosineFrequency * t / (float) sampleRate);
                sineAtT = (float) Math.sin(2 * Math.PI * cosineFrequency * t / (float) sampleRate);
                for (int i = 0; i < 256; i++) {
                    cosineRealLookupTable[t][i] = (i - 127.4f) / 128.0f * cosineAtT;
                    cosineImagLookupTable[t][i] = (i - 127.4f) / 128.0f * sineAtT;
                }
            }
            cosineIndex = 0;
        }
    }

    /**
     * 将字节数组组装成数据包
     *
     * @param packet
     * @param samplePacket
     * @return
     */
    @Override
    public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket) {
        int capacity = samplePacket.capacity();
        int count = 0;
        int startIndex = samplePacket.size();
        float[] re = samplePacket.re();
        float[] im = samplePacket.im();
        for (int i = 0; i < packet.length; i += 2) {
            re[startIndex + count] = lookupTable[packet[i] & 0xff];
            im[startIndex + count] = lookupTable[packet[i + 1] & 0xff];
            count++;
            if (startIndex + count >= capacity)
                break;
        }
        samplePacket.setSize(samplePacket.size() + count);    // 更新样本包的大小
        samplePacket.setSampleRate(sampleRate);                // 更新采样率
        samplePacket.setFrequency(frequency);                // 更新频率
        return count;
    }

    /**
     * 开启解调器后调用
     *
     * @param packet
     * @param samplePacket
     * @param channelFrequency
     * @return
     */
    @Override
    public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency) {
        int mixFrequency = (int) (frequency - channelFrequency);

        generateMixerLookupTable(mixFrequency);    // will only generate table if really necessary

        // Mix the samples from packet and store the results in the samplePacket
        int capacity = samplePacket.capacity();
        int count = 0;


        int startIndex = samplePacket.size();
        float[] re = samplePacket.re();
        float[] im = samplePacket.im();
        for (int i = 0; i < packet.length; i += 2) {
            re[startIndex + count] = cosineRealLookupTable[cosineIndex][packet[i] & 0xff] - cosineImagLookupTable[cosineIndex][packet[i + 1] & 0xff];
            im[startIndex + count] = cosineRealLookupTable[cosineIndex][packet[i + 1] & 0xff] + cosineImagLookupTable[cosineIndex][packet[i] & 0xff];
            cosineIndex = (cosineIndex + 1) % cosineRealLookupTable.length;
            count++;
            if (startIndex + count >= capacity)
                break;
        }
        samplePacket.setSize(samplePacket.size() + count);    // 更新样本包的大小
        samplePacket.setSampleRate(sampleRate);                // 更新采样率
        samplePacket.setFrequency(channelFrequency);        // 更新频率
//        Logger.i("channelFrequency:"+channelFrequency);
        return count;
    }
}
