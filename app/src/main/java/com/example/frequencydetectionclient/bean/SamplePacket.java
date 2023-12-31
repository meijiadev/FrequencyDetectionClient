package com.example.frequencydetectionclient.bean;

/**
 * Module:      SamplePacket.java
 * Description: 这个类封装了一个复杂样本包。
 */
public class SamplePacket {
	private float[] re;			// real values
	private float[] im;			// imag values
	private long frequency;		// center frequency
	private int sampleRate;		// sample rate
	private int size;			// number of samples in this packet

	/**
	 * Constructor. This constructor wraps existing arrays and set the number of
	 * samples to the length of the arrays
	 *
	 * @param re			样本值实部的数组
	 * @param im			样本值的虚部数组
	 * @param frequency		center frequency
	 * @param sampleRate	sample rate
	 */
	public SamplePacket(float[] re, float im[], long frequency, int sampleRate) {
		this(re, im, frequency, sampleRate, re.length);
	}

	/**
	 * Constructor. This constructor wraps existing arrays and allows to set the
	 * number of samples in this packet to something smaller than the array length
	 *
	 * @param re			样本值实部的数组
	 * @param im			样本值的虚部数组
	 * @param frequency		center frequency
	 * @param sampleRate	sample rate
	 * @param size	number of samples in this packet ( <= arrays.length )
	 */
	public SamplePacket(float[] re, float im[], long frequency, int sampleRate, int size) {
		if(re.length != im.length)
			throw new IllegalArgumentException("Arrays must be of the same length");
		if(size > re.length)
			throw new IllegalArgumentException("Size must be of the smaller or equal the array length");
		this.re = re;
		this.im = im;
		this.frequency = frequency;
		this.sampleRate = sampleRate;
		this.size = size;
	}

	/**
	 * Constructor. This constructor allocates two fresh arrays
	 *
	 * @param size	Number of samples in this packet
	 */
	public SamplePacket(int size) {
		this.re = new float[size];
		this.im = new float[size];
		this.frequency = 0;
		this.sampleRate = 0;
		this.size = 0;
	}

	/**
	 * @return the reference to the array of real parts
	 */
	public float[] re() {
		return re;
	}

	/**
	 * Returns the real part at the specified index
	 *
	 * @param i		index
	 * @return real part of the sample with the given index
	 */
	public float re(int i) {
		return re[i];
	}

	/**
	 * @return the reference to the array of imaginary parts
	 */
	public float[] im() {
		return im;
	}

	/**
	 * Returns the imaginary part at the specified index
	 *
	 * @param i		index
	 * @return imaginary part of the sample with the given index
	 */
	public float im(int i) {
		return im[i];
	}

	/**
	 * @return the length of the arrays
	 */
	public int capacity() {
		return re.length;
	}

	/**
	 * @return number of samples in this packet
	 */
	public int size() {
		return size;
	}

	/**
	 * Sets a new size (number of samples in this packet)
	 * @param size	number of (valid) samples in this packet
	 */
	public void setSize(int size) {
		this.size = Math.min(size, re.length);
	}

	/**
	 * @return center frequency at which these samples where recorded
	 */
	public long getFrequency() {
		return frequency;
	}

	/**
	 * @return sample rate at which these samples were recorded
	 */
	public int getSampleRate() {
		return sampleRate;
	}

	/**
	 * Sets the center frequency for this sample packet
	 * @param frequency		center frequency at which these samples were recorded
	 */
	public void setFrequency(long frequency) {
		this.frequency = frequency;
	}

	/**
	 * Sets the sample rate for this sample packet
	 * @param sampleRate		sample rate at which these samples were recorded
	 */
	public void setSampleRate(int sampleRate) {
		this.sampleRate = sampleRate;
	}
}
