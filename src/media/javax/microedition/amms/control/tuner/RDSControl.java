package javax.microedition.amms.control.tuner;

import javax.microedition.media.Control;
import javax.microedition.media.MediaException;

import java.util.Date;

/**
 * RDSControl controls the features of a tuner with RDS
 * (Radio Data System) support.
 */
public interface RDSControl extends Control {
	String RDS_NEW_DATA = "RDS_NEW_DATA";
	String RDS_NEW_ALARM = "RDS_ALARM";
	String RADIO_CHANGED = "radio_changed";

	boolean isRDSSignal();

	String getPS();

	String getRT();

	short getPTY();

	String getPTYString(boolean useStringTable);

	short getPI();

	int[] getFreqsByPTY(short pty);

	int[][] getFreqsByTA(boolean useTA);

	String[] getPSByPTY(short pty);

	String[] getPSByTA(boolean useTA);

	Date getCT();

	boolean getTA();

	boolean getTP();

	void setAutomaticSwitching(boolean state) throws MediaException;

	boolean getAutomaticSwitching();

	void setAutomaticTA(boolean state) throws MediaException;

	boolean getAutomaticTA();
}
