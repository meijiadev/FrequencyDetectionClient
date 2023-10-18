package com.example.frequencydetectionclient.hackrf;

import android.content.Context;
import android.util.Log;

import com.example.frequencydetectionclient.bean.SamplePacket;
import com.example.frequencydetectionclient.iq.IQSourceInterface;
import com.example.frequencydetectionclient.utils.Signed8BitIQConverter;
import com.example.frequencydetectionclient.iq.IQConverter;
import com.mantz_it.hackrf_android.Hackrf;
import com.mantz_it.hackrf_android.HackrfCallbackInterface;
import com.mantz_it.hackrf_android.HackrfUsbException;
import com.orhanobut.logger.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Module:      HackrfSource.java
 * Description: 代表HackRF设备的源类。
 */
public class HackrfSource implements IQSourceInterface, HackrfCallbackInterface {
	private Hackrf hackrf = null;
	private String name = null;
	private Callback callback = null;
	private ArrayBlockingQueue<byte[]> queue = null;
	private long frequency = 0;
	private int sampleRate = 0;
	private int basebandFilterWidth = 0;
	private boolean automaticBBFilterCalculation = true;
	private int vgaRxGain = 0;
	private int vgaTxGain = 0;
	private int lnaGain = 0;
	private boolean amplifier = false;
	private boolean antennaPower = false;
	private int frequencyOffset = 0;	// virtually offset the frequency according to an external up/down-converter
	private IQConverter iqConverter;
//	private static final String LOGTAG = "HackRFSource";
	public static final long MIN_FREQUENCY = 1l;
	public static final long MAX_FREQUENCY = 7250000000l;
	public static final int MAX_SAMPLERATE = 20000000;
	public static final int MIN_SAMPLERATE = 4000000;
	public static final int MAX_VGA_RX_GAIN = 62;
	public static final int MAX_VGA_TX_GAIN = 47;
	public static final int MAX_LNA_GAIN = 40;
	public static final int VGA_RX_GAIN_STEP_SIZE = 2;
	public static final int VGA_TX_GAIN_STEP_SIZE = 1;
	public static final int LNA_GAIN_STEP_SIZE = 8;
	public static final int[] OPTIMAL_SAMPLE_RATES = { 4000000, 6000000, 8000000, 10000000, 12500000, 16000000, 20000000};

	public HackrfSource() {
		iqConverter = new Signed8BitIQConverter();
	}

	/**
	 * Will forward an error message to the callback object
	 *
	 * @param msg	error message
	 */
	private void reportError(String msg) {
		if(callback != null)
			callback.onIQSourceError(this,msg);
		else
			Logger.e("reportError: Callback is null. (Error: " + msg + ")");
	}

	@Override
	public boolean open(Context context, Callback callback) {
		int queueSize = 1000000;
		this.callback = callback;
		// Initialize the HackRF (i.e. open the USB device, which requires the user to give permissions)
		return Hackrf.initHackrf(context, this, queueSize);
	}

	@Override
	public boolean close() {
		return true;
	}

	@Override
	public void onHackrfReady(Hackrf hackrf) {
		this.hackrf = hackrf;
		if(callback != null)
			callback.onIQSourceReady(this);
	}

	@Override
	public void onHackrfError(String message) {
		Logger.e("Error while opening HackRF: " + message);
		reportError(message);
	}

	@Override
	public boolean isOpen() {
		if(hackrf == null)
			return false;
		try {
			hackrf.getBoardID();	// this will only succeed if the hackrf is ready/open
			return true;	// no exception was thrown --> hackrf is open!
		} catch (HackrfUsbException e) {
			return false;	// exception was thrown --> hackrf is not open
		}
	}

	@Override
	public String getName() {
		if(name == null && hackrf != null) {
			try {
				name = Hackrf.convertBoardIdToString(hackrf.getBoardID());
			} catch (HackrfUsbException e) {
			}
		}
		if(name != null)
			return name;
		else
			return "HackRF";
	}

	@Override
	public long getFrequency() {
		return frequency + frequencyOffset;
	}

	@Override
	public void setFrequency(long frequency) {
		long actualFrequency = frequency - frequencyOffset;
		// re-tune the hackrf:
		if(hackrf != null) {
			try {
				hackrf.setFrequency(actualFrequency);
			} catch (HackrfUsbException e) {
				Logger.e("setFrequency: Error while setting frequency: " + e.getMessage());
				reportError("Error while setting frequency");
				return;
			}
		}

		//刷新队列：
		this.flushQueue();

		// 存储新频率
		this.frequency = actualFrequency;
		this.iqConverter.setFrequency(frequency);
	}

	@Override
	public long getMaxFrequency() {
		return MAX_FREQUENCY + frequencyOffset;
	}

	@Override
	public long getMinFrequency() {
		return MIN_FREQUENCY + frequencyOffset;
	}

	@Override
	public int getMaxSampleRate() {
		return MAX_SAMPLERATE;
	}

	@Override
	public int getMinSampleRate() {
		return MIN_SAMPLERATE;
	}

	@Override
	public int getSampleRate() {
		return sampleRate;
	}

	@Override
	public int getNextHigherOptimalSampleRate(int sampleRate) {
		for (int opt : OPTIMAL_SAMPLE_RATES) {
			if (sampleRate < opt)
				return opt;
		}
		return OPTIMAL_SAMPLE_RATES[OPTIMAL_SAMPLE_RATES.length-1];
	}

	@Override
	public int getNextLowerOptimalSampleRate(int sampleRate) {
		for (int i = 1; i < OPTIMAL_SAMPLE_RATES.length; i++) {
			if(sampleRate <= OPTIMAL_SAMPLE_RATES[i])
				return OPTIMAL_SAMPLE_RATES[i-1];
		}
		return OPTIMAL_SAMPLE_RATES[OPTIMAL_SAMPLE_RATES.length-1];
	}

	@Override
	public int[] getSupportedSampleRates() {
		return OPTIMAL_SAMPLE_RATES;
	}

	@Override
	public void setSampleRate(int sampleRate) {
		if(isAutomaticBBFilterCalculation())
			setBasebandFilterWidth((int)(sampleRate * 0.75));

		// 将hackrf设置为新的采样率：
		if(hackrf != null) {
			try {
				hackrf.setSampleRate(sampleRate,1);
				hackrf.setBasebandFilterBandwidth(basebandFilterWidth);
			} catch (HackrfUsbException e) {
				Logger.e("setSampleRate: Error while setting sample rate: " + e.getMessage());
				reportError("Error while setting sample rate");
				return;
			}
		}

		// Flush the queue
		this.flushQueue();
		this.sampleRate = sampleRate;
		this.iqConverter.setSampleRate(sampleRate);
		Logger.i("设置的采样率："+sampleRate/1000/1000 +"Mhz");
	}

	public int getBasebandFilterWidth() {
		return basebandFilterWidth;
	}

	public boolean isAutomaticBBFilterCalculation() {
		return automaticBBFilterCalculation;
	}

	public int getVgaRxGain() {
		return vgaRxGain;
	}

	public int getVgaTxGain() {
		return vgaTxGain;
	}

	public int getLnaGain() {
		return lnaGain;
	}

	public boolean isAmplifierOn() {
		return amplifier;
	}

	public boolean isAntennaPowerOn() {
		return antennaPower;
	}

	public void setBasebandFilterWidth(int basebandFilterWidth) {
		this.basebandFilterWidth = hackrf.computeBasebandFilterBandwidth(basebandFilterWidth);
		Logger.i("setBasebandFilterWidth: Setting BB filter width to " + this.basebandFilterWidth);
		if(hackrf != null) {
			try {
				hackrf.setBasebandFilterBandwidth(this.basebandFilterWidth);
			} catch (HackrfUsbException e) {
				Logger.e("setBasebandFilterWidth: Error while setting base band filter width: " + e.getMessage());
				reportError("Error while setting base band filter width");
			}
		}
	}

	public void setAutomaticBBFilterCalculation(boolean automaticBBFilterCalculation) {
		this.automaticBBFilterCalculation = automaticBBFilterCalculation;
	}

	public void setVgaRxGain(int vgaRxGain) {
		if(vgaRxGain > MAX_VGA_RX_GAIN) {
			Logger.e("setVgaRxGain: Value (" + vgaRxGain + ") too high. Maximum is: " + MAX_VGA_RX_GAIN);
			return;
		}

		if(hackrf != null) {
			try {
				hackrf.setRxVGAGain(vgaRxGain);
			} catch (HackrfUsbException e) {
				Logger.e("setVgaRxGain: Error while setting vga gain: " + e.getMessage());
				reportError("Error while setting vga gain");
				return;
			}
		}
		this.vgaRxGain = vgaRxGain;
	}

	public void setVgaTxGain(int vgaTxGain) {
		if(vgaTxGain > MAX_VGA_TX_GAIN) {
			Logger.e("setVgaTxGain: Value (" + vgaTxGain + ") too high. Maximum is: " + MAX_VGA_TX_GAIN);
			return;
		}

		if(hackrf != null) {
			try {
				hackrf.setTxVGAGain(vgaTxGain);
			} catch (HackrfUsbException e) {
				Logger.e("setVgaTxGain: Error while setting vga gain: " + e.getMessage());
				reportError("Error while setting vga gain");
				return;
			}
		}
		this.vgaTxGain = vgaTxGain;
	}

	public void setLnaGain(int lnaGain) {
		if(lnaGain > MAX_LNA_GAIN) {
			Logger.e("setLnaGain: Value (" + lnaGain + ") too high. Maximum is: " + MAX_LNA_GAIN);
			return;
		}

		if(hackrf != null) {
			try {
				hackrf.setRxLNAGain(lnaGain);
			} catch (HackrfUsbException e) {
				Logger.e("setLnaGain: Error while setting lna gain: " + e.getMessage());
				reportError("Error while setting lna gain");
				return;
			}
		}
		this.lnaGain = lnaGain;
	}

	public void setAmplifier(boolean amplifier) {
		if(hackrf != null) {
			try {
				hackrf.setAmp(amplifier);
			} catch (HackrfUsbException e) {
				Logger.e("setAmplifier: Error while setting amplifier: " + e.getMessage());
				reportError("Error while setting amplifier state");
				return;
			}
		}
		this.amplifier = amplifier;
	}

	public void setAntennaPower(boolean antennaPower) {
		if(hackrf != null) {
			try {
				hackrf.setAntennaPower(antennaPower);
			} catch (HackrfUsbException e) {
				Logger.e("setAntennaPower: Error while setting antenna power: " + e.getMessage());
				reportError("Error while setting antenna power state");
				return;
			}
		}
		this.antennaPower = antennaPower;
	}

	public int getFrequencyOffset() {
		return frequencyOffset;
	}

	public void setFrequencyOffset(int frequencyOffset) {
		this.frequencyOffset = frequencyOffset;
		this.iqConverter.setFrequency(frequency+frequencyOffset);
	}

	@Override
	public int getPacketSize() {
		if(hackrf != null)
			return hackrf.getPacketSize();
		else {
			Logger.e("getPacketSize: Hackrf instance is null");
			return 0;
		}
	}

	@Override
	public byte[] getPacket(int timeout) {
		if(queue != null && hackrf != null) {
			try {
				byte[] packet = queue.poll(timeout, TimeUnit.MILLISECONDS);
				if(packet == null && (hackrf.getTransceiverMode() != Hackrf.HACKRF_TRANSCEIVER_MODE_RECEIVE)) {
					Logger.e("getPacket: HackRF is not in receiving mode!");
					reportError("HackRF stopped receiving");
				}
				return packet;
			} catch (InterruptedException e) {
				Logger.e("getPacket: Interrupted while waiting on queue");
				return null;
			}
		}
		else {
			Logger.e("getPacket: Queue is null");
			return null;
		}
	}

	@Override
	public void returnPacket(byte[] buffer) {
		if(hackrf != null)
			hackrf.returnBufferToBufferPool(buffer);
		else {
			Logger.e("returnPacket: Hackrf instance is null");
		}
	}

	@Override
	public void startSampling() {
		if(hackrf != null) {
			try {
				hackrf.setSampleRate(sampleRate, 1);
				hackrf.setFrequency(frequency);
				hackrf.setBasebandFilterBandwidth(basebandFilterWidth);
				hackrf.setRxVGAGain(vgaRxGain);
				hackrf.setRxLNAGain(lnaGain);
				hackrf.setAmp(amplifier);
				hackrf.setAntennaPower(antennaPower);
				this.queue = hackrf.startRX();
				Logger.i("startSampling: Started HackRF with: sampleRate="+sampleRate+" frequency="+frequency
							+ " basebandFilterWidth="+basebandFilterWidth+" rxVgaGain="+vgaRxGain+" lnaGain="+lnaGain
							+ " amplifier="+amplifier+" antennaPower="+antennaPower);
			} catch (HackrfUsbException e) {
				Logger.e("startSampling: Error while set up hackrf: " + e.getMessage());
			}
		} else {
			Logger.e( "startSampling: Hackrf instance is null");
		}
	}

	@Override
	public void stopSampling() {
		if(hackrf != null) {
			try {
				hackrf.stop();
			} catch (HackrfUsbException e) {
				Logger.e( "stopSampling: Error while tear down hackrf: " + e.getMessage());
			}
		} else {
			Logger.e( "stopSampling: Hackrf instance is null");
		}
	}

	@Override
	public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket) {
		return this.iqConverter.fillPacketIntoSamplePacket(packet, samplePacket);
	}

	public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency) {
		return this.iqConverter.mixPacketIntoSamplePacket(packet, samplePacket, channelFrequency);
	}

	/**
	 * 将清空队列
	 */
	public void flushQueue() {
		byte[] buffer;

		if(hackrf == null || queue == null)
			return; //没有什么可清空的。。。

		for (int i = 0; i < queue.size(); i++) {
			buffer = queue.poll();
			if(buffer == null)
				return; // 结束方法；队列为空。
			hackrf.returnBufferToBufferPool(buffer);
		}
	}
}
