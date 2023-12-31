package com.example.frequencydetectionclient.iq;

import android.content.Context;

import com.example.frequencydetectionclient.bean.SamplePacket;

/**
 * Module:      IQSourceInterface.java
 * Description: 此接口表示IQ样本的来源。它允许调度程序独立于SDR硬件。
 */
public interface IQSourceInterface {

	/**
	 * Will open the device. This is usually an asynchronous action and therefore uses the
	 * callback function onIQSourceReady() from the Callback interface to notify the application
	 * when the IQSource is ready to use.
	 *
	 * @param context		needed to open external devices
	 * @param callback		reference to a class that implements the Callback interface for notification
	 * @return false if an error occurred.
	 */
	public boolean open(Context context, Callback callback);

	/**
	 * Will return true if the source is opened and ready to use
	 *
	 * @return true if open, false if not open
	 */
	public boolean isOpen();

	/**
	 * Will close the device.
	 *
	 * @return false if an error occurred.
	 */
	public boolean close();

	/**
	 * @return a human readable Name of the source
	 */
	public String getName();

	/**
	 * @return the rate at which this source receives samples
	 */
	public int getSampleRate();

	/**
	 * @param sampleRate	sample rate that should be set for the IQ source
	 */
	public void setSampleRate(int sampleRate);

	/**
	 * @return the Frequency to which the source is tuned
	 */
	public long getFrequency();

	/**
	 * @param frequency		frequency to which the IQ source should be tuned
	 */
	public void setFrequency(long frequency);


	/**
	 * @return the maximum frequency to which the source can be tuned
	 */
	public long getMaxFrequency();

	/**
	 * @return the minimum frequency to which the source can be tuned
	 */
	public long getMinFrequency();

	/**
	 * @return the maximum sample rate to which the source can be set
	 */
	public int getMaxSampleRate();

	/**
	 * @return the minimum sample rate to which the source can be set
	 */
	public int getMinSampleRate();

	/**
	 * @param sampleRate	initial sample rate for the lookup
	 * @return next optimal sample rate that is higher than sampleRate
	 */
	public int getNextHigherOptimalSampleRate(int sampleRate);

	/**
	 * @param sampleRate	initial sample rate for the lookup
	 * @return next optimal sample rate that is lower than sampleRate
	 */
	public int getNextLowerOptimalSampleRate(int sampleRate);

	/**
	 * @return Array of all supported (optimal) sample rates
	 */
	public int[] getSupportedSampleRates();

	/**
	 * @return the size (in byte) of a packet that is returned by getPacket()
	 */
	public int getPacketSize();

	/**
	 * This method will grab the next packet from the source and return it. If no
	 * packet is available after the timeout, null is returned. Make sure to return
	 * the packet to the buffer pool by using returnPacket() after it is no longer used.
	 *
	 * @return packet containing received samples
	 */
	public byte[] getPacket(int timeout);

	/**
	 * This method will return the given buffer (packet) to the buffer pool of the
	 * source instance.
	 *
	 * @param buffer	A packet that was returned by getPacket() and is now no longer used
	 */
	public void returnPacket(byte[] buffer);

	/**
	 * Start receiving samples.
	 */
	public void startSampling();

	/**
	 * Stop receiving samples.
	 */
	public void stopSampling();

	/**
	 * Used to convert a packet from this source to the SamplePacket format. That means the samples
	 * in the SamplePacket are stored as signed double values, normalized between -1 and 1.
	 * Note that samples are appended to the buffer starting at the index samplePacket.size().
	 * If you want to overwrite, set the size to 0 first.
	 *
	 * @param packet		packet that was returned by getPacket() and that should now be 'filled'
	 *                      into the samplePacket.
	 * @param samplePacket	SamplePacket that should be filled with samples from the packet.
	 * @return the number of samples filled into the samplePacket.
	 */
	public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket);

	/**
	 * Used to convert a packet from this source to the SamplePacket format while at the same
	 * time mixing the signal with the specified frequency. That means the samples
	 * in the SamplePacket are stored as signed double values, normalized between -1 and 1.
	 * Note that samples are appended to the buffer starting at the index samplePacket.size().
	 * If you want to overwrite, set the size to 0 first.
	 *
	 * @param packet			packet that was returned by getPacket() and that should now be 'filled'
	 *                          into the samplePacket.
	 * @param samplePacket		SamplePacket that should be filled with samples from the packet.
	 * @param channelFrequency	frequency to which the spectrum of the signal should be shifted
	 * @return the number of samples filled into the samplePacket and shifted by mixFrequency.
	 */
	public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency);

	/**
	 * Callback interface for asynchronous interactions with the source.
	 */
	public static interface Callback {
		/**
		 * This method will be called when the source is ready to use after the application
		 * called open()
		 *
		 * @param source	A reference to the IQSource that is now ready
		 */
		public void onIQSourceReady(IQSourceInterface source);

		/**
		 * This method will be called when there is an error with the source
		 *
		 * @param source	A reference to the IQSource that caused the error
		 * @param message	Description of the error
		 */
		public void onIQSourceError(IQSourceInterface source, String message);
	}
}
