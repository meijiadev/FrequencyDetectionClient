package com.example.frequencydetectionclient.thread;

import com.example.frequencydetectionclient.bean.SamplePacket;
import com.example.frequencydetectionclient.utils.FirFilter;
import com.example.frequencydetectionclient.hackrf.HalfBandLowPassFilter;
import com.orhanobut.logger.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <h1>RF Analyzer - Decimator</h1>
 *
 * Module:      Decimator.java
 * Description: 这个类实现了一个抽取块，用于将输入信号降采样到解调例程使用的采样率。它将在一个单独的线程中运行。
 *
 * @author Dennis Mantz

 */
public class Decimator extends Thread {
	private int outputSampleRate;	// sample rate at the output of the decimator block
	private int packetSize;			// packet size of the incoming packets
	private boolean stopRequested = true;

	private static final int OUTPUT_QUEUE_SIZE = 2;		// Double Buffer
	private ArrayBlockingQueue<SamplePacket> inputQueue;		// 保存传入样本数据包的队列
	private ArrayBlockingQueue<SamplePacket> inputReturnQueue;	// 队列从输入队列返回已使用的缓冲区
	private ArrayBlockingQueue<SamplePacket> outputQueue;		// 将保存抽取的样本数据包的队列
	private ArrayBlockingQueue<SamplePacket> outputReturnQueue;	// 队列从输出队列返回已使用的缓冲区

	// DOWNSAMPLING:
	private static final int INPUT_RATE = 1000000;	// For now, this decimator only works with a fixed input rate of 1Msps
	private HalfBandLowPassFilter inputFilter1 = null;
	private HalfBandLowPassFilter inputFilter2 = null;
	private HalfBandLowPassFilter inputFilter3 = null;
	private FirFilter inputFilter4 = null;
	private SamplePacket tmpDownsampledSamples;

	/**
	 * Constructor. Will create a new Decimator block.
	 *
	 * @param outputSampleRate		// sample rate to which the incoming samples should be decimated
	 * @param packetSize			// packet size of the incoming sample packets
	 * @param inputQueue			// queue that delivers incoming sample packets
	 * @param inputReturnQueue		// queue to return used input sample packets
	 */
	public Decimator (int outputSampleRate, int packetSize, ArrayBlockingQueue<SamplePacket> inputQueue,
					  ArrayBlockingQueue<SamplePacket> inputReturnQueue) {
		this.outputSampleRate = outputSampleRate;
		this.packetSize = packetSize;
		this.inputQueue = inputQueue;
		this.inputReturnQueue = inputReturnQueue;

		// 创建输出队列:
		this.outputQueue = new ArrayBlockingQueue<SamplePacket>(OUTPUT_QUEUE_SIZE);
		this.outputReturnQueue = new ArrayBlockingQueue<SamplePacket>(OUTPUT_QUEUE_SIZE);
		for (int i = 0; i < OUTPUT_QUEUE_SIZE; i++)
			outputReturnQueue.offer(new SamplePacket(packetSize));

		// Create half band filters for downsampling:
		this.inputFilter1 = new HalfBandLowPassFilter(8);
		this.inputFilter2 = new HalfBandLowPassFilter(8);
		this.inputFilter3 = new HalfBandLowPassFilter(8);

		// Create local buffers:
		this.tmpDownsampledSamples = new SamplePacket(packetSize);
	}

	public int getOutputSampleRate() {
		return outputSampleRate;
	}

	public void setOutputSampleRate(int outputSampleRate) {
		this.outputSampleRate = outputSampleRate;
	}

	public SamplePacket getDecimatedPacket(int timeout) {
		try {
			return outputQueue.poll(timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Logger.e("getPacket: Interrupted while waiting on queue");
			return null;
		}
	}

	public void returnDecimatedPacket(SamplePacket packet) {
		outputReturnQueue.offer(packet);
	}

	@Override
	public synchronized void start() {
		this.stopRequested = false;
		super.start();
	}

	public void stopDecimator() {
		this.stopRequested = true;
	}

	@Override
	public void run() {
		SamplePacket inputSamples;
		SamplePacket outputSamples;

		Logger.i("Decimator started. (Thread: " + this.getName() + ")");

		while (!stopRequested) {
			// 从输入队列中获取一个数据包:
			try {
				inputSamples = inputQueue.poll(1000, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Logger.e("run: Interrupted while waiting on input queue! stop.");
				this.stopRequested = true;
				break;
			}

			// 验证输入的样本包不为空:
			if (inputSamples == null) {
				//Logger.d("run: Input sample is null. skip this round...");
				continue;
			}

			//验证输入采样率:(目前，这个十进制器只能在1Msps的固定输入率下工作)
			if (inputSamples.getSampleRate() != INPUT_RATE) {
				Logger.d("run: Input sample rate is " + inputSamples.getSampleRate() + " but should be" + INPUT_RATE + ". skip.");
				continue;
			}

			// Get a packet from the output queue:
			try {
				outputSamples = outputReturnQueue.poll(1000, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Logger.e("run: Interrupted while waiting on output return queue! stop.");
				this.stopRequested = true;
				break;
			}

			// Verify the output sample packet is not null:
			if (outputSamples == null) {
				Logger.d("run: Output sample is null. skip this round...");
				continue;
			}

			// downsampling
			downsampling(inputSamples, outputSamples);

			// 将inputSamples返回到输入队列:
			inputReturnQueue.offer(inputSamples);

			// 将outputSamples交付到输出队列
			outputQueue.offer(outputSamples);
		}

		this.stopRequested = true;
		Logger.i("Decimator stopped. (Thread: " + this.getName() + ")");
	}

	/**
	 * Will decimate the input samples to the outputSampleRate and store them in output
	 *
	 * @param input		incoming samples at the incoming rate (input rate)
	 * @param output	outgoing (decimated) samples at output rate (quadrature rate)
	 */
	private void downsampling(SamplePacket input, SamplePacket output) {
		// Verify that the input filter 4 is still correct configured (gain):
		if(inputFilter4 == null || inputFilter4.getGain() != 2*(outputSampleRate/(double)input.getSampleRate()) ) {
			// We have to (re-)create the filter:
			this.inputFilter4 = FirFilter.createLowPass(2, 2*(outputSampleRate/(float)input.getSampleRate()), 1, 0.15f, 0.2f, 20);
			Logger.d("downsampling: created new inputFilter4 with " + inputFilter4.getNumberOfTaps()
					+ " taps. Decimation=" + inputFilter4.getDecimation() + " Cut-Off=" + inputFilter4.getCutOffFrequency()
					+ " transition=" + inputFilter4.getTransitionWidth());
		}

		// apply first filter (decimate to INPUT_RATE/2)
		tmpDownsampledSamples.setSize(0);	// mark buffer as empty
		if (inputFilter1.filterN8(input, tmpDownsampledSamples, 0, input.size()) < input.size()) {
			Logger.e("downsampling: [inputFilter1] could not filter all samples from input packet.");
		}

		// if we need a decimation of 16: apply second and third filter (decimate to INPUT_RATE/8)
		if(input.getSampleRate()/outputSampleRate == 16) {
			output.setSize(0);	// mark buffer as empty
			if (inputFilter2.filterN8(tmpDownsampledSamples, output, 0, tmpDownsampledSamples.size()) < tmpDownsampledSamples.size()) {
				Logger.e("downsampling: [inputFilter2] could not filter all samples from input packet.");
			}

			tmpDownsampledSamples.setSize(0);	// mark tmp buffer as again
			if (inputFilter3.filterN8(output, tmpDownsampledSamples, 0, output.size()) < output.size()) {
				Logger.e("downsampling: [inputFilter3] could not filter all samples from input packet.");
			}
		}

		// apply fourth filter (decimate either to INPUT_RATE/4 or INPUT_RATE/16)
		output.setSize(0);	// mark buffer as empty
		if (inputFilter4.filter(tmpDownsampledSamples, output, 0, tmpDownsampledSamples.size()) < tmpDownsampledSamples.size()) {
			Logger.e("downsampling: [inputFilter4] could not filter all samples from input packet.");
		}
	}
}
