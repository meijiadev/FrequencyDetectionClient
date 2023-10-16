package com.example.frequencydetectionclient.thread;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.example.frequencydetectionclient.bean.SamplePacket;
import com.example.frequencydetectionclient.utils.FirFilter;
import com.orhanobut.logger.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Module:      AudioSink.java
 * Description: 此类实现到系统音频API的接口。它将在一个单独的线程中运行，并缓冲传入的样本数据包在阻塞队列中。输入分组是解调的（真实的）信号。此类将根据音频速率。
 */
public class AudioSink extends Thread {
	private AudioTrack audioTrack = null;		// AudioTrack object that is used to pass audio samples to the Android system
	private boolean stopRequested = true;
	private ArrayBlockingQueue<SamplePacket> inputQueue = null;		// Queue that holds incoming samples
	private ArrayBlockingQueue<SamplePacket> outputQueue = null;	// Queue that holds available buffers
	private int packetSize;		// packet size of the incoming sample packets
	private int sampleRate;		// audio sample rate of the AudioSink
	private static final int QUEUE_SIZE = 2;	// This results in a double buffer. see Scheduler...
	private FirFilter audioFilter1 = null;		// Filter used to decimate the incoming signal rate
	private FirFilter audioFilter2 = null;		// Cascaded filter for high incoming signal rates
	private SamplePacket tmpAudioSamples;		// tmp buffer for audio filters.

	/**
	 * Constructor. Will create a new AudioSink.
	 *
	 * @param packetSize	size of the incoming packets
	 * @param sampleRate	sample rate of the audio signal
	 */
	public AudioSink (int packetSize, int sampleRate) {
		this.packetSize = packetSize;
		this.sampleRate = sampleRate;

		// Create the queues and fill them with
		this.inputQueue = new ArrayBlockingQueue<SamplePacket>(QUEUE_SIZE);
		this.outputQueue = new ArrayBlockingQueue<SamplePacket>(QUEUE_SIZE);
		for (int i = 0; i < QUEUE_SIZE; i++)
			this.outputQueue.offer(new SamplePacket(packetSize));

		// 创建AudioTrack类的一个实例:
		int bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
		this.audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
									AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);

		// Create the audio filters:
		this.audioFilter1 = FirFilter.createLowPass(2, 1, 1, 0.1f, 0.15f, 30);
		Logger.d("constructor: created audio filter 1 with " + audioFilter1.getNumberOfTaps() + " Taps.");
		this.audioFilter2 = FirFilter.createLowPass(4, 1, 1, 0.1f, 0.1f, 30);
		Logger.d("constructor: created audio filter 2 with " + audioFilter2.getNumberOfTaps() + " Taps.");
		this.tmpAudioSamples = new SamplePacket(packetSize);
	}

	/**
	 * Starts the thread
	 */
	@Override
	public synchronized void start() {
		stopRequested = false;
		super.start();
	}

	/**
	 * Stops the thread
	 */
	public void stopSink () {
		stopRequested = true;
	}

	/**
	 * @return size of the packets that are offered by getPacketBuffer()
	 */
	public int getPacketSize() {
		return packetSize;
	}

	/**
	 * The AudioSink allocates the buffers for audio playback. Use this method to request
	 * a free buffer. This method will block if no buffer is available.
	 *
	 * @param timeout	max time this method will block
	 * @return free buffer or null if no buffer available
	 */
	public SamplePacket getPacketBuffer(int timeout) {
		try {
			return outputQueue.poll(timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Logger.e("getPacketBuffer: Interrupted. return null...");
			return null;
		}
	}

	/**
	 * Enqueues a packet buffer for being played on the audio track.
	 *
	 * @param packet	the packet buffer from getPacketBuffer() filled with samples
	 * @return true if success, false if error
	 */
	public boolean enqueuePacket(SamplePacket packet) {
		if(packet == null) {
			Logger.e("enqueuePacket: Packet is null.");
			return false;
		}
		if(!inputQueue.offer(packet)) {
			Logger.e("enqueuePacket: Queue is full.");
			return false;
		}
		return true;
	}

	@Override
	public void run() {
		SamplePacket packet;
		SamplePacket filteredPacket;
		SamplePacket tempPacket = new SamplePacket(packetSize);
		float[] floatPacket;
		short[] shortPacket = new short[packetSize];

		Logger.i("AudioSink started. (Thread: " + this.getName() + ")"+stopRequested);

		// start audio playback:
		audioTrack.play();

		// Continuously write the data from the queue to the audio track:
		while (!stopRequested) {
			try {
				// Get the next packet from the queue
				packet = inputQueue.poll(1000, TimeUnit.MILLISECONDS);

				if(packet == null) {
					//Logger.d("run: Queue is empty. skip this round");
					continue;
				}

				// apply audio filter (decimation)
				if(packet.getSampleRate() > this.sampleRate) {
					applyAudioFilter(packet, tempPacket);
					filteredPacket = tempPacket;
				}
				else
					filteredPacket = packet;

				// Convert doubles to shorts [expect doubles to be in [-1...1]
				floatPacket = filteredPacket.re();
				for (int i = 0; i < filteredPacket.size(); i++) {
					shortPacket[i] = (short) (floatPacket[i] * 32767);
				}

				// Write it to the audioTrack:
				if(audioTrack.write(shortPacket, 0, filteredPacket.size()) != filteredPacket.size()) {
					Logger.e("run: write() returned with error! stop");
					stopRequested = true;
				}

				// Return the buffer to the output queue
				outputQueue.offer(packet);
			} catch (InterruptedException e) {
				Logger.e("run: Interrupted while polling from queue. stop");
				stopRequested = true;
			}
		}

		// stop audio playback:
		audioTrack.stop();
		this.stopRequested = true;
		Logger.i("AudioSink stopped. (Thread: " + this.getName() + ")");
	}

	/**
	 * Will filter the real array contained in input and decimate them to the audio rate.
	 *
	 * @param input		incoming (unfiltered) samples at the incoming rate (quadrature rate)
	 * @param output	outgoing (filtered, decimated) samples at audio rate
	 */
	public void applyAudioFilter(SamplePacket input, SamplePacket output) {
		// if we need a decimation of 8: apply first and second filter (decimate to input_rate/8)
		if(input.getSampleRate()/sampleRate == 8) {
			// apply first filter (decimate to input_rate/2)
			tmpAudioSamples.setSize(0);	// mark buffer as empty
			if (audioFilter1.filterReal(input, tmpAudioSamples, 0, input.size()) < input.size()) {
				Logger.e("applyAudioFilter: [audioFilter1] could not filter all samples from input packet.");
			}

			// apply second filter (decimate to input_rate/8)
			output.setSize(0);
			if (audioFilter2.filterReal(tmpAudioSamples, output, 0, tmpAudioSamples.size()) < tmpAudioSamples.size()) {
				Logger.e("applyAudioFilter: [audioFilter2] could not filter all samples from input packet.");
			}
		} else if (input.getSampleRate()/sampleRate == 2){
			// apply first filter (decimate to input_rate/2 )
			output.setSize(0);
			if (audioFilter1.filterReal(input, output, 0, input.size()) < input.size()) {
				Logger.e("applyAudioFilter: [audioFilter1] could not filter all samples from input packet.");
			}
		} else
			Logger.e("applyAudioFilter: incoming sample rate is not supported!");
	}
}
