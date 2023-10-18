package com.example.frequencydetectionclient.thread;

import com.example.frequencydetectionclient.iq.IQSourceInterface;
import com.example.frequencydetectionclient.bean.SamplePacket;
import com.orhanobut.logger.Logger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * <p>
 * Module:      Scheduler.java
 * Description: 此线程负责转发来自输入硬件的样本以正确的速度和格式发送到解调器和处理环路。
 * 样本数据包通过使用阻塞队列传递到其他块。通过的样本到解调器的信号将首先转移到基带。
 * 如果解调器或处理环路变慢，调度程序将自动丢弃传入的样本以防止hackrfandroid库的缓冲区被填满。
 */
public class Scheduler extends Thread {
    private IQSourceInterface source = null;    // Reference to the source of the IQ samples
    private ArrayBlockingQueue<SamplePacket> fftOutputQueue = null;    // 将样本传送到处理循环的队列
    private ArrayBlockingQueue<SamplePacket> fftInputQueue = null;    // Queue that collects used buffers from the Processing Loop
    private ArrayBlockingQueue<SamplePacket> demodOutputQueue = null;    // 将采样发送到解调器块的队列
    private ArrayBlockingQueue<SamplePacket> demodInputQueue = null;    // Queue that collects used buffers from the Demodulator block
    private long channelFrequency = 0;                    // 当将数据包传递到解调器时，将频率移到此值
    private boolean demodulationActivated = false;        // 指示采样是否应该转发到解调器队列。
    private boolean squelchSatisfied = false;            // 指示当前信号是否强到足以越过静噪阈值
    private boolean stopRequested = true;
    private BufferedOutputStream bufferedOutputStream = null;    // Used for recording
    private boolean stopRecording = false;

    // 定义fft输出和输入队列的大小。通过将该值设置为2，我们基本上结束具有双重缓冲。也许这两个队列太夸张了，但它像这样工作得很好它为我们处理调度程序线程和处理循环之间的同步。
    // 请注意，将大小设置为1效果不佳，任何大于2的数字都会导致切换频率时延迟更高。
    private static final int FFT_QUEUE_SIZE = 2;
    private static final int DEMOD_QUEUE_SIZE = 20;

    public Scheduler(int fftSize, IQSourceInterface source) {
        this.source = source;

        //创建fft输入和输出队列并分配缓冲数据包。
        this.fftOutputQueue = new ArrayBlockingQueue<SamplePacket>(FFT_QUEUE_SIZE);
        this.fftInputQueue = new ArrayBlockingQueue<SamplePacket>(FFT_QUEUE_SIZE);
        for (int i = 0; i < FFT_QUEUE_SIZE; i++)
            fftInputQueue.offer(new SamplePacket(fftSize));

        // 创建demod输入和输出队列并分配缓冲区数据包。
        this.demodOutputQueue = new ArrayBlockingQueue<SamplePacket>(DEMOD_QUEUE_SIZE);
        this.demodInputQueue = new ArrayBlockingQueue<SamplePacket>(DEMOD_QUEUE_SIZE);
        for (int i = 0; i < DEMOD_QUEUE_SIZE; i++)
            demodInputQueue.offer(new SamplePacket(source.getPacketSize()));
    }

    public void stopScheduler() {
        this.stopRequested = true;
        this.source.stopSampling();
    }

    public void start() {
        this.stopRequested = false;
        this.source.startSampling();
        super.start();
        Logger.i("启动线程");
    }

    /**
     * @return true if scheduler is running; false if not.
     */
    public boolean isRunning() {
        return !stopRequested;
    }

    public ArrayBlockingQueue<SamplePacket> getFftOutputQueue() {
        return fftOutputQueue;
    }

    public ArrayBlockingQueue<SamplePacket> getFftInputQueue() {
        return fftInputQueue;
    }

    public ArrayBlockingQueue<SamplePacket> getDemodOutputQueue() {
        return demodOutputQueue;
    }

    public ArrayBlockingQueue<SamplePacket> getDemodInputQueue() {
        return demodInputQueue;
    }

    public boolean isDemodulationActivated() {
        return demodulationActivated;
    }

    public void setDemodulationActivated(boolean demodulationActivated) {
        this.demodulationActivated = demodulationActivated;
        Logger.i("解调激活-demodulationActivated：" + demodulationActivated);
    }

    public long getChannelFrequency() {
        return channelFrequency;
    }

    public void setChannelFrequency(long channelFrequency) {
        this.channelFrequency = channelFrequency;
        Logger.i("channelFrequency：" + channelFrequency);
    }

    /**
     * 当所选信道的信号强度超过静噪阈值时必须调用
     *
     * @param squelchSatisfied true: the signal is now stronger than the threshold; false: signal is now weaker
     */
    public void setSquelchSatisfied(boolean squelchSatisfied) {
        this.squelchSatisfied = squelchSatisfied;
        Logger.i("squelchSatisfied:" + squelchSatisfied);
    }

    /**
     * 将停止向bufferedOutputStream写入样本并关闭它。
     */
    public void stopRecording() {
        this.stopRecording = true;
    }

    /**
     * 将开始将原始样本写入bufferedOutputStream。流将在出现错误时关闭，在stopRecording（）和stopSampling（）时关闭
     *
     * @param bufferedOutputStream stream to write the samples out.
     */
    public void startRecording(BufferedOutputStream bufferedOutputStream) {
        this.stopRecording = false;
        this.bufferedOutputStream = bufferedOutputStream;
        Logger.i("startRecording: Recording started.");
    }

    /**
     * @return true 如果当前正在录制；如果不是，则为false
     */
    public boolean isRecording() {
        return bufferedOutputStream != null;
    }

    @Override
    public void run() {
        Logger.e("Scheduler started. (Thread: " + this.getName() + ")");
        SamplePacket fftBuffer = null;           // 引用我们从fft输入队列中获得的要填充的缓冲区
        SamplePacket demodBuffer = null;        // 引用我们从demod输入队列中获得的要填充的缓冲区
        SamplePacket tmpFlushBuffer = null;    // 如果需要，只需要一个tmp缓冲区来刷新队列
        // 从源获取一个新数据包:
        // byte[] packet = source.getPacket(1000);
        while (!stopRequested) {
            // 从源获取一个新数据包:
            byte[] packet = source.getPacket(1000);
            if (packet == null) {
                Logger.e("run: No more packets from source. Shutting down...");
                this.stopScheduler();
                break;
            }

            ///// Recording ///////////////////////////
            if (bufferedOutputStream != null) {
                try {
                    bufferedOutputStream.write(packet);
                } catch (IOException e) {
                    Logger.e("run: Error while writing to output stream (recording): " + e.getMessage());
                    this.stopRecording();
                }
                if (stopRecording) {
                    try {
                        bufferedOutputStream.close();
                    } catch (IOException e) {
                        Logger.e("run: Error while closing output stream (recording): " + e.getMessage());
                    }
                    bufferedOutputStream = null;
                    Logger.i("run: Recording stopped.");
                }
            }
            ///// 解调制 //////////////////////////////
            if (demodulationActivated && squelchSatisfied) {
                // 从解调器inputQueue获取缓冲区
                demodBuffer = demodInputQueue.poll();
                if (demodBuffer != null) {
                    demodBuffer.setSize(0);    // 将缓冲区标记为空
                    // 将数据包填充到缓冲区中，并通过mixFrequency移动其频谱:
                    source.mixPacketIntoSamplePacket(packet, demodBuffer, channelFrequency);
                    demodOutputQueue.offer(demodBuffer);    // 提供包
                } else {
                    Logger.d("run: Flush the demod queue because demodulator is too slow!");
                    while ((tmpFlushBuffer = demodOutputQueue.poll()) != null)
                        demodInputQueue.offer(tmpFlushBuffer);
                }
            }

            ///// FFT /////////////////////////////
            // 如果buffer为null，则从fft输入队列请求一个新的缓冲区:
            if (fftBuffer == null) {
                fftBuffer = fftInputQueue.poll();
                if (fftBuffer != null)
                    fftBuffer.setSize(0);    // mark buffer as empty
            }
            // 如果有缓冲区，就填满它!
            if (fftBuffer != null) {
                // 将数据包填充到缓冲区中:
                source.fillPacketIntoSamplePacket(packet, fftBuffer);
                // 检查缓冲区现在是否已满，如果已满:将其传递到输出队列
                if (fftBuffer.capacity() == fftBuffer.size()) {
                    fftOutputQueue.offer(fftBuffer);
                    fftBuffer = null;
                }
                // 否则我们就再来一轮……
            }

            // 如果buffer为null，我们目前没有可用的缓冲区，这意味着我们只是把样本扔掉(这种情况在大多数情况下都会发生)。
            // 在这两种情况下:将数据包返回到源缓冲池:
            source.returnPacket(packet);
        }
        this.stopRequested = true;
        if (bufferedOutputStream != null) {
            try {
                bufferedOutputStream.close();
            } catch (IOException e) {
                Logger.e("run: Error while closing output stream (cleanup)(recording): " + e.getMessage());
            }
            bufferedOutputStream = null;
        }
        Logger.i("Scheduler stopped. (Thread: " + this.getName() + ")");
    }
}
