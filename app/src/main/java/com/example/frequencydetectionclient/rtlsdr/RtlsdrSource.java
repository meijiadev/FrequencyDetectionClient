package com.example.frequencydetectionclient.rtlsdr;

import android.content.Context;

import com.example.frequencydetectionclient.bean.SamplePacket;
import com.example.frequencydetectionclient.iq.IQConverter;
import com.example.frequencydetectionclient.iq.IQSourceInterface;
import com.example.frequencydetectionclient.utils.Unsigned8BitIQConverter;
import com.orhanobut.logger.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Module:      RtlsdrSource.java
 * Description: Simple source of IQ sampling by reading from IQ files generated by the
 * HackRF. Just for testing.
 */
public class RtlsdrSource implements IQSourceInterface {
    public static final int RTLSDR_TUNER_UNKNOWN = 0;
    public static final int RTLSDR_TUNER_E4000 = 1;
    public static final int RTLSDR_TUNER_FC0012 = 2;
    public static final int RTLSDR_TUNER_FC0013 = 3;
    public static final int RTLSDR_TUNER_FC2580 = 4;
    public static final int RTLSDR_TUNER_R820T = 5;
    public static final int RTLSDR_TUNER_R828D = 6;
    public static final String[] TUNER_STRING = {"UNKNOWN", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D"};

    /**
     * 设置频率的命令
     */
    public static final int RTL_TCP_COMMAND_SET_FREQUENCY = 0x01;
    /**
     * 设置采样率的命令
     */
    public static final int RTL_TCP_COMMAND_SET_SAMPLERATE = 0x02;

    /**
     * 设置增益模式
     */
    public static final int RTL_TCP_COMMAND_SET_GAIN_MODE = 0x03;
    /**
     * 增益命令
     */
    public static final int RTL_TCP_COMMAND_SET_GAIN = 0x04;
    /**
     * 频率校正
     */
    public static final int RTL_TCP_COMMAND_SET_FREQ_CORR = 0x05;
    /**
     * 中频增益
     */
    public static final int RTL_TCP_COMMAND_SET_IFGAIN = 0x06;
    /**
     * AGC模式
     */
    public static final int RTL_TCP_COMMAND_SET_AGC_MODE = 0x08;
    public final String[] COMMAND_NAME = {"invalid", "SET_FREQUENY", "SET_SAMPLERATE", "SET_GAIN_MODE",
            "SET_GAIN", "SET_FREQ_CORR", "SET_IFGAIN", "SET_TEST_MODE", "SET_ADC_MODE"};

    private ReceiverThread receiverThread = null;
    private CommandThread commandThread = null;
    private Callback callback = null;
    private Socket socket = null;
    private InputStream inputStream = null;
    private OutputStream outputStream = null;
    private String name = "RTL-SDR";
    private String magic = null;
    private int tuner = RTLSDR_TUNER_UNKNOWN;
    private String ipAddress = "127.0.0.1";
    private int port = 1234;
    private ArrayBlockingQueue<byte[]> queue = null;
    private ArrayBlockingQueue<byte[]> returnQueue = null;
    /**
     * 中心频率
     */
    private long frequency = 0;
    /**
     * 采样率
     */
    private int sampleRate = 0;
    private int gain = 0;
    private int ifGain = 0;
    private boolean manualGain = true;    // true == manual; false == automatic
    private int frequencyCorrection = 0;
    private boolean automaticGainControl = false;
    private int frequencyOffset = 0;    //根据外部上/下转换器虚拟地偏移频率
    private IQConverter iqConverter;

    private static final int QUEUE_SIZE = 20;
    /**
     * 最佳采样率
     */
    public static final int[] OPTIMAL_SAMPLE_RATES = {1000000, 1024000, 1800000, 1920000, 2000000, 2048000, 2400000};
    public static final long[] MIN_FREQUENCY = {0,            // invalid
            52000000l,    // E4000
            22000000l,    // FC0012
            22000000l,    // FC0013
            146000000l,    // FC2580
            24000000l,    // R820T
            24000000l};    // R828D            // 24Mhz -
    public static final long[] MAX_FREQUENCY = {0l,            // invalid
            3000000000l,    // E4000		实际最大频率: 2200000000l
            3000000000l,    // FC0012		实际最大频率: 948000000l
            3000000000l,    // FC0013		实际最大频率: 1100000000l
            3000000000l,    // FC2580		实际最大频率: 924000000l
            3000000000l,    // R820T		实际最大频率: 1766000000l
            3000000000l};    // R828D		实际最大频率: 1766000000l          3000 mhz
    public static final int[][] POSSIBLE_GAIN_VALUES = {    // Values from gr_osmocom rt_tcp_source_s.cc:
            {0},                                                                        // invalid
            {-10, 15, 40, 65, 90, 115, 140, 165, 190, 215, 240, 290, 340, 420},            // E4000
            {-99, -40, 71, 179, 192},                                                    // FC0012
            {-99, -73, -65, -63, -60, -58, -54, 58, 61, 63, 65, 67, 68,
                    70, 71, 179, 181, 182, 184, 186, 188, 191, 197},                    // FC0013
            {0},                                                                        // FC2580
            {0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280,
                    297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496},    // R820T
            {0}                                                                            // R828D ??
    };
    public static final int PACKET_SIZE = 16384;

    public RtlsdrSource(String ip, int port) {
        this.ipAddress = ip;
        this.port = port;

        // 创建队列和缓冲区：
        queue = new ArrayBlockingQueue<byte[]>(QUEUE_SIZE);
        returnQueue = new ArrayBlockingQueue<byte[]>(QUEUE_SIZE);
        for (int i = 0; i < QUEUE_SIZE; i++)
            returnQueue.offer(new byte[PACKET_SIZE]);

        this.iqConverter = new Unsigned8BitIQConverter();
    }

    /**
     * 将向回调对象转发错误消息
     *
     * @param msg error message
     */
    private void reportError(String msg) {
        if (callback != null)
            callback.onIQSourceError(this, msg);
        else
            Logger.e("reportError: Callback is null. (Error: " + msg + ")");
    }

    /**
     * 如果ip地址是环回并连接到rtl_tcp实例，这将启动RTL2832U驱动程序应用程序
     *
     * @param context  not used
     * @param callback reference to a class that implements the Callback interface for notification
     * @return
     */
    @Override
    public boolean open(Context context, Callback callback) {
        this.callback = callback;

        // 启动命令线程（这将执行“打开”过程：
        //连接到rtltcp实例，读取信息并通知回调处理程序
        if (commandThread != null) {
            Logger.e("open：命令线程仍在运行");
            reportError("打开设备时出错");
            return false;
        }
        commandThread = new CommandThread();
        commandThread.start();

        return true;
    }

    @Override
    public boolean isOpen() {
        return (commandThread != null);
    }

    @Override
    public boolean close() {
        // Stop receving:
        if (receiverThread != null)
            stopSampling();

        // 停止命令线程：
        if (commandThread != null) {
            commandThread.stopCommandThread();
            // 仅当当前线程不是命令线程时才加入线程^^
            if (!Thread.currentThread().getName().equals(commandThread.threadName)) {
                try {
                    commandThread.join();
                } catch (InterruptedException e) {
                }
            }
            commandThread = null;
        }

        this.tuner = 0;
        this.magic = null;
        this.name = "RTL-SDR";
        return true;
    }

    @Override
    public String getName() {
        return name;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }

    @Override
    public int getSampleRate() {
        return sampleRate;
    }

    @Override
    public void setSampleRate(int sampleRate) {
        if (isOpen()) {
            if (sampleRate < getMinSampleRate() || sampleRate > getMaxSampleRate()) {
                Logger.e("setSampleRate：采样率超出有效范围： " + sampleRate);
                return;
            }

            if (!commandThread.executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_SAMPLERATE, sampleRate))) {
                Logger.e("setSampleRate: failed.");
            }
        }

        // Flush the queue:
        this.flushQueue();

        this.sampleRate = sampleRate;
        this.iqConverter.setSampleRate(sampleRate);
        Logger.i("采样率：" + sampleRate);
    }

    @Override
    public long getFrequency() {
        return frequency + frequencyOffset;
    }

    @Override
    public void setFrequency(long frequency) {
        long actualSourceFrequency = frequency - frequencyOffset;
        if (isOpen()) {
            if (frequency < getMinFrequency() || frequency > getMaxFrequency()) {
                Logger.e("setFrequency:频率超出有效范围： " + frequency
                        + "  (upconverterFrequency=" + frequencyOffset + " is subtracted!)");
                return;
            }
          //  Logger.i("frequency:" + frequency);
            commandThread.executeFrequencyChangeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_FREQUENCY, (int) actualSourceFrequency));
        }

        // Flush the queue:
        this.flushQueue();

        this.frequency = actualSourceFrequency;
        this.iqConverter.setFrequency(frequency);
    }

    @Override
    public long getMaxFrequency() {
        return MAX_FREQUENCY[tuner] + frequencyOffset;
    }

    @Override
    public long getMinFrequency() {
        return MIN_FREQUENCY[tuner] + frequencyOffset;
    }

    @Override
    public int getMaxSampleRate() {
        return OPTIMAL_SAMPLE_RATES[OPTIMAL_SAMPLE_RATES.length - 1];
    }

    @Override
    public int getMinSampleRate() {
        return OPTIMAL_SAMPLE_RATES[0];
    }

    @Override
    public int getNextHigherOptimalSampleRate(int sampleRate) {
        for (int opt : OPTIMAL_SAMPLE_RATES) {
            if (sampleRate < opt)
                return opt;
        }
        return OPTIMAL_SAMPLE_RATES[OPTIMAL_SAMPLE_RATES.length - 1];
    }

    @Override
    public int getNextLowerOptimalSampleRate(int sampleRate) {
        for (int i = 1; i < OPTIMAL_SAMPLE_RATES.length; i++) {
            if (sampleRate <= OPTIMAL_SAMPLE_RATES[i])
                return OPTIMAL_SAMPLE_RATES[i - 1];
        }
        return OPTIMAL_SAMPLE_RATES[OPTIMAL_SAMPLE_RATES.length - 1];
    }

    @Override
    public int[] getSupportedSampleRates() {
        return OPTIMAL_SAMPLE_RATES;
    }

    public boolean isManualGain() {
        return manualGain;
    }

    public void setManualGain(boolean enable) {
        if (isOpen()) {
            if (!commandThread.executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_GAIN_MODE, (int) (enable ? 0x01 : 0x00)))) {
                Logger.e("setManualGain: failed.");
            }
        }
        this.manualGain = enable;
    }

    public int getGain() {
        return gain;
    }

    public int[] getPossibleGainValues() {
        return POSSIBLE_GAIN_VALUES[tuner];
    }

    public void setGain(int gain) {
        if (isOpen()) {
            if (!commandThread.executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_GAIN, gain))) {
                Logger.e("setGain: failed.");
            }
        }
        this.gain = gain;
    }

    public int getIFGain() {
        return ifGain;
    }

    public int[] getPossibleIFGainValues() {
        if (tuner == RTLSDR_TUNER_E4000) {
            int[] ifGainValues = new int[54];
            for (int i = 0; i < ifGainValues.length; i++)
                ifGainValues[i] = i + 3;
            return ifGainValues;
        } else {
            return new int[]{0};
        }
    }

    public void setIFGain(int ifGain) {
        if (isOpen() && tuner == RTLSDR_TUNER_E4000) {
            if (!commandThread.executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_IFGAIN, (short) 0, (short) ifGain))) {
                Logger.e("setIFGain: failed.");
            }
        }
        this.ifGain = ifGain;
    }

    public int getFrequencyCorrection() {
        return frequencyCorrection;
    }

    public void setFrequencyCorrection(int ppm) {
        if (isOpen()) {
            if (!commandThread.executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_FREQ_CORR, ppm))) {
                Logger.e("setFrequencyCorrection: failed.");
            }
        }
        this.frequencyCorrection = ppm;
    }

    public boolean isAutomaticGainControl() {
        return automaticGainControl;
    }

    public void setAutomaticGainControl(boolean enable) {
        if (isOpen()) {
            if (!commandThread.executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_AGC_MODE, (int) (enable ? 0x01 : 0x00)))) {
                Logger.e("setAutomaticGainControl: failed.");
            }
        }
        this.automaticGainControl = enable;
    }

    public int getFrequencyOffset() {
        return frequencyOffset;
    }

    public void setFrequencyOffset(int frequencyShift) {
        this.frequencyOffset = frequencyShift;
        this.iqConverter.setFrequency(frequency + frequencyShift);
        Logger.i("frequencyShift：" + frequencyShift);
    }

    @Override
    public int getPacketSize() {
        return PACKET_SIZE;
    }

    @Override
    public byte[] getPacket(int timeout) {
        if (queue != null) {
            try {
                return queue.poll(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Logger.e("getPacket: Interrupted while polling packet from queue: " + e.getMessage());
            }
        } else {
            Logger.e("getPacket: Queue is null");
        }
        return null;
    }

    @Override
    public void returnPacket(byte[] buffer) {
        if (returnQueue != null) {
            returnQueue.offer(buffer);
        } else {
            Logger.e("returnPacket: Return queue is null");
        }
    }

    /**
     * 开始采样
     */
    @Override
    public void startSampling() {
        if (receiverThread != null) {
            Logger.e("startSampling: receiver thread still running.");
            reportError("Could not start sampling");
            return;
        }

        if (isOpen()) {
            // start ReceiverThread:
            receiverThread = new ReceiverThread(inputStream, returnQueue, queue);
            receiverThread.start();
        }
    }

    /**
     * 停止采样
     */
    @Override
    public void stopSampling() {
        // stop and join receiver thread:
        if (receiverThread != null) {
            receiverThread.stopReceiving();
            // 仅当当前线程不是receiverThread时才加入线程^^
            if (!Thread.currentThread().getName().equals(receiverThread.threadName)) {
                try {
                    receiverThread.join();
                } catch (InterruptedException e) {
                    Logger.e("stopSampling：加入接收器线程时中断: " + e.getMessage());
                }
            }
            receiverThread = null;
        }
    }

    /**
     * 将数据包填充到样本数据包中
     *
     * @param packet       packet that was returned by getPacket() and that should now be 'filled'
     *                     into the samplePacket.
     * @param samplePacket SamplePacket that should be filled with samples from the packet.
     * @return
     */
    @Override
    public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket) {
        return this.iqConverter.fillPacketIntoSamplePacket(packet, samplePacket);
    }

    @Override
    public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency) {
        return this.iqConverter.mixPacketIntoSamplePacket(packet, samplePacket, channelFrequency);
    }

    /**
     * 将清空队列
     */
    public void flushQueue() {
        byte[] buffer;

        for (int i = 0; i < QUEUE_SIZE; i++) {
            buffer = queue.poll();
            if (buffer == null)
                return; // we are done; the queue is empty.
            this.returnPacket(buffer);
        }
    }

    /**
     * 将rtl_tcp命令打包到字节缓冲区
     *
     * @param command RTL_TCP_COMMAND_*
     * @param arg     command argument (see rtl_tcp documentation)
     * @return command buffer
     */
    private byte[] commandToByteArray(int command, int arg) {
        byte[] commandArray = new byte[5];
        commandArray[0] = (byte) command;
        commandArray[1] = (byte) ((arg >> 24) & 0xff);
        commandArray[2] = (byte) ((arg >> 16) & 0xff);
        commandArray[3] = (byte) ((arg >> 8) & 0xff);
        commandArray[4] = (byte) (arg & 0xff);
        return commandArray;
    }

    /**
     * 将rtl_tcp命令打包到字节缓冲区
     *
     * @param command RTL_TCP_COMMAND_*
     * @param arg1    first command argument (see rtl_tcp documentation)
     * @param arg2    second command argument (see rtl_tcp documentation)
     * @return command buffer
     */
    private byte[] commandToByteArray(int command, short arg1, short arg2) {
        byte[] commandArray = new byte[5];
        commandArray[0] = (byte) command;
        commandArray[1] = (byte) ((arg1 >> 8) & 0xff);
        commandArray[2] = (byte) (arg1 & 0xff);
        commandArray[3] = (byte) ((arg2 >> 8) & 0xff);
        commandArray[4] = (byte) (arg2 & 0xff);
        return commandArray;
    }

    /**
     * 此线程将从套接字中读取样本并将其放入队列中
     */
    private class ReceiverThread extends Thread {
        public String threadName = null;    // 我们保存线程名称以在stopSampling（）方法中进行检查
        private boolean stopRequested = false;
        private InputStream inputStream = null;
        private ArrayBlockingQueue<byte[]> inputQueue = null;
        private ArrayBlockingQueue<byte[]> outputQueue = null;

        public ReceiverThread(InputStream inputStream, ArrayBlockingQueue<byte[]> inputQueue, ArrayBlockingQueue<byte[]> outputQueue) {
            this.inputStream = inputStream;
            this.inputQueue = inputQueue;
            this.outputQueue = outputQueue;
        }

        public void stopReceiving() {
            this.stopRequested = true;
        }

        public void run() {
            byte[] buffer = null;
            int index = 0;
            int bytesRead = 0;

            Logger.i("ReceiverThread started (Thread: " + this.getName() + ")");
            threadName = this.getName();

            while (!stopRequested) {
                try {
                    // 如果buffer为null，则从inputQueue请求一个新的buffer:
                    if (buffer == null) {
                        buffer = inputQueue.poll(1000, TimeUnit.MILLISECONDS);
                        index = 0;
                    }

                    if (buffer == null) {
                        Logger.e("ReceiverThread: Couldn't get buffer from input queue. stop.");
                        this.stopRequested = true;
                        break;
                    }
                    if (inputStream==null){
                        Logger.i("inputStream 为null");
                    }
                    // 从inputStream中读入缓冲区:
                    bytesRead = inputStream.read(buffer, index, buffer.length - index);

                    if (bytesRead <= 0) {
                        Logger.e("ReceiverThread: Couldn't read data from input stream. stop.");
                        this.stopRequested = true;
                        break;
                    }

                    index += bytesRead;
                    if (index == buffer.length) {
                        // 缓冲区已满。将其发送到输出队列:
                        outputQueue.offer(buffer);
                        buffer = null;
                    }

                } catch (InterruptedException e) {
                    Logger.e("ReceiverThread: 等待时中断: " + e.getMessage());
                    this.stopRequested = true;
                    break;
                } catch (IOException e) {
                    Logger.e("ReceiverThread: 从socket读取时出错: " + e.getMessage());
                    reportError("Error while receiving samples.");
                    this.stopRequested = true;
                    break;
                } catch (NullPointerException e) {
                     Logger.e("ReceiverThread: Nullpointer! (Probably inputStream): " + e.getStackTrace());
                    this.stopRequested = true;
                    break;
                }
            }
            // 检查我们是否仍保留缓冲区并将其返回到输入队列：
            if (buffer != null)
                inputQueue.offer(buffer);

            Logger.i("ReceiverThread stopped (Thread: " + this.getName() + ")");
        }
    }

    /**
     * 该线程将启动与rtl_tcp实例的连接，然后向它命令可以排队等待其他线程执行
     */
    private class CommandThread extends Thread {
        public String threadName = null;    // 我们保存线程名称以在close（）方法中进行检查
        private ArrayBlockingQueue<byte[]> commandQueue = null;
        private static final int COMMAND_QUEUE_SIZE = 20;
        private ArrayBlockingQueue<byte[]> frequencyChangeCommandQueue = null;    // 频率更改的单独队列（解决方案）
        private boolean stopRequested = false;

        public CommandThread() {
            // Create command queue:
            this.commandQueue = new ArrayBlockingQueue<byte[]>(COMMAND_QUEUE_SIZE);
            this.frequencyChangeCommandQueue = new ArrayBlockingQueue<byte[]>(1);    // work-around
        }

        public void stopCommandThread() {
            this.stopRequested = true;
        }

        /**
         * 将调度命令（将其放入命令队列
         *
         * @param command 5字节命令数组（请参阅rtl_tcp文档）
         * @return true 如果命令已排定；
         */
        public boolean executeCommand(byte[] command) {
            Logger.d("executeCommand: Queuing command: " + COMMAND_NAME[command[0]]);
            if (commandQueue.offer(command))
                return true;

            // Queue is full
            // todo: maybe flush the queue? for now just error:
            Logger.e("executeCommand: command queue is full!");
            return false;
        }

        /**
         * 解决方法：
         * 频率变化经常发生，如果向驾驶员发送了太多这样的命令
         * 它将滞后并最终崩溃。为了防止这种情况，我们有一个单独的commandQueue，仅用于
         * 频率变化。此队列的大小为1，executeFrequencyChangeCommand（）将确保
         * 它总是包含最新的频率改变命令。命令线程将始终休眠250毫秒
         * 在执行频率改变命令以防止高速率的命令之后。
         *
         * @param command 5 byte command array (see rtl_tcp documentation)
         */
        public void executeFrequencyChangeCommand(byte[] command) {
            // 从队列中删除任何等待频率更改命令(不再使用):
            frequencyChangeCommandQueue.poll();
            frequencyChangeCommandQueue.offer(command);    // will always work
        }

        /**
         * 从run（）调用；将设置到rtl_tcp实例的连接
         */
        private boolean connect(int timeoutMillis) {
            if (socket != null) {
                Logger.e("connect: Socket is still connected");
                return false;
            }

            // 连接到远程/本地rtl_tcp
            try {
                long timeoutTime = System.currentTimeMillis() + timeoutMillis;
                while (!stopRequested && socket == null && System.currentTimeMillis() < timeoutTime) {
                    try {
                        socket = new Socket(ipAddress, port);
                    } catch (IOException e) {
                        // ignore...
                    }
                    sleep(100);
                }

                if (socket == null) {
                    if (stopRequested)
                        Logger.i("CommandThread: (connect) command thread stopped while connecting.");
                    else
                        Logger.e("CommandThread: (connect) hit timeout");
                    return false;
                }

                // Set socket options:
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(1000);

                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
                byte[] buffer = new byte[4];

                // Read magic value:
                if (inputStream.read(buffer, 0, buffer.length) != buffer.length) {
                    Logger.e("CommandThread: (connect) Could not read magic value");
                    return false;
                }
                magic = new String(buffer, "ASCII");

                // 读调谐器类型:
                if (inputStream.read(buffer, 0, buffer.length) != buffer.length) {
                    Logger.e("CommandThread: (connect) 无法读取调谐器类型");
                    return false;
                }
                tuner = buffer[3];
                if (tuner <= 0 || tuner >= TUNER_STRING.length) {
                    Logger.e("CommandThread: (connect) 调谐器类型无效");
                    return false;
                }

                // 读取增益计数（仅用于调试。目前未使用该值）
                if (inputStream.read(buffer, 0, buffer.length) != buffer.length) {
                    Logger.e("CommandThread: (connect) 无法读取增益计数");
                    return false;
                }

                Logger.i("CommandThread: (connect) Connected to RTL-SDR (Tuner: " + TUNER_STRING[tuner] + ";  magic: " + magic +
                        ";  gain count: " + buffer[3] + ") at " + ipAddress + ":" + port);

                // 使用新信息更新源名称：
                name = "RTL-SDR (" + TUNER_STRING[tuner] + ") at " + ipAddress + ":" + port;
                Logger.i("---------" + name);
                // 检查参数是否在范围内并进行更正：
                if (frequency > MAX_FREQUENCY[tuner]) {
                    frequency = MAX_FREQUENCY[tuner];
                }
                if (frequency < MIN_FREQUENCY[tuner]) {
                    frequency = MIN_FREQUENCY[tuner];
                }
                iqConverter.setFrequency(frequency + frequencyOffset);
                if (sampleRate > getMaxSampleRate())
                    sampleRate = getMaxSampleRate();
                if (sampleRate < getMinSampleRate())
                    sampleRate = getMinSampleRate();
                for (int gainStep : getPossibleGainValues()) {
                    if (gainStep >= gain) {
                        gain = gainStep;
                        break;
                    }
                }

                // 设置所有参数:
                //频率
                executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_FREQUENCY, (int) frequency));

                // 采样率
                executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_SAMPLERATE, sampleRate));

                // Gain Mode:
                executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_GAIN_MODE, (int) (manualGain ? 0x01 : 0x00)));

                // Gain:
                if (manualGain)
                    executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_GAIN, gain));

                // IFGain:
                if (manualGain && tuner == RTLSDR_TUNER_E4000)
                    executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_IFGAIN, (short) 0, (short) ifGain));

                // Frequency Correction:
                executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_FREQ_CORR, frequencyCorrection));

                // AGC mode:
                executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_AGC_MODE, (int) (automaticGainControl ? 0x01 : 0x00)));

                return true;

            } catch (UnknownHostException e) {
                Logger.e("CommandThread: (connect) Unknown host: " + ipAddress);
                reportError("Unknown host: " + ipAddress);
            } catch (IOException e) {
                Logger.e("CommandThread: (connect) Error while connecting to rtlsdr://" + ipAddress + ":" + port + " : " + e.getMessage());
            } catch (InterruptedException e) {
                Logger.e("CommandThread: (connect) Interrupted.");
            }
            return false;
        }

        public void run() {
            Logger.i("CommandThread started (Thread: " + this.getName() + ")");
            threadName = this.getName();
            byte[] nextCommand = null;

            // Perfom "device open". This means connect to the rtl_tcp instance; get the information
            if (connect(10000)) {    // 10 seconds for the user to accept permission request
                // report that the device is ready:
                callback.onIQSourceReady(RtlsdrSource.this);
            } else {
                if (!stopRequested) {
                    Logger.e("CommandThread: (open) connect reported error.");
                    reportError("Couldn't connect to rtl_tcp instance");
                    stopRequested = true;
                }
                // else: thread was stopped while connecting...
            }

            // 从队列中轮询命令并通过socket循环发送它们:
            while (!stopRequested && outputStream != null) {
                try {
                    nextCommand = commandQueue.poll(100, TimeUnit.MILLISECONDS);

                    // 解决方法：
                    // 频率变化经常发生，如果向驱动发送了太多这样的命令它将滞后并最终崩溃。为了防止这种情况，我们有一个单独的commandQueue，仅用于频率变化。
                    // 此队列的大小为1，executeFrequencyChangeCommand（）将确保它总是包含最新的频率改变命令。命令线程将始终休眠100毫秒在执行频率改变命令以防止高速率的命令之后。
                    if (nextCommand == null)
                        nextCommand = frequencyChangeCommandQueue.poll(); // 检查频率变化命令：

                    if (nextCommand == null)
                        continue;
                    outputStream.write(nextCommand);
                    Logger.d("CommandThread: Command was sent: " + COMMAND_NAME[nextCommand[0]]);
                } catch (IOException e) {
                    Logger.e("CommandThread: Error while sending command (" + COMMAND_NAME[nextCommand[0]] + "): " + e.getMessage());
                    reportError("Error while sending command: " + COMMAND_NAME[nextCommand[0]]);
                    break;
                } catch (InterruptedException e) {
                    Logger.e("CommandThread: Interrupted while sending command (" + COMMAND_NAME[nextCommand[0]] + ")");
                    reportError("Interrupted while sending command: " + COMMAND_NAME[nextCommand[0]]);
                    break;
                }
            }

            // Clean up:
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            socket = null;
            inputStream = null;
            outputStream = null;
            RtlsdrSource.this.commandThread = null;        // mark this source as 'closed'
            Logger.i("CommandThread stopped (Thread: " + this.getName() + ")");
        }
    }
}
