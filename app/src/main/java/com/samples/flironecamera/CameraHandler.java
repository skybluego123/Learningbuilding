/*******************************************************************
 * @title FLIR THERMAL SDK
 * @file CameraHandler.java
 * @Author FLIR Systems AB
 *
 * @brief Helper class that encapsulates *most* interactions with a FLIR ONE camera
 *
 * Copyright 2019:    FLIR Systems
 ********************************************************************/
package com.samples.flironecamera;

import android.graphics.Bitmap;
import android.util.Log;

import com.flir.thermalsdk.androidsdk.image.BitmapAndroid;
import com.flir.thermalsdk.image.ColorDistribution;
import com.flir.thermalsdk.image.Point;
import com.flir.thermalsdk.image.Rectangle;
import com.flir.thermalsdk.image.ThermalImage;
import com.flir.thermalsdk.image.fusion.FusionMode;
import com.flir.thermalsdk.image.palettes.Palette;
import com.flir.thermalsdk.image.palettes.PaletteManager;
import com.flir.thermalsdk.live.Camera;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.ConnectParameters;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.live.discovery.DiscoveryFactory;
import com.flir.thermalsdk.live.streaming.ThermalImageStreamListener;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static java.lang.Math.floor;

/**
 * Encapsulates the handling of a FLIR ONE camera or built in emulator, discovery, connecting and start receiving images.
 * All listeners are called from Thermal SDK on a non-ui thread
 * <p/>
 * Usage:
 * <pre>
 * Start discovery of FLIR FLIR ONE cameras or built in FLIR ONE cameras emulators
 * {@linkplain #startDiscovery(DiscoveryEventListener, DiscoveryStatus)}
 * Use a discovered Camera {@linkplain Identity} and connect to the Camera
 * (note that calling connect is blocking and it is mandatory to call this function from a background thread):
 * {@linkplain #connect(Identity, ConnectionStatusListener)}
 * Once connected to a camera
 * {@linkplain #startStream(StreamDataListener)}
 * </pre>
 * <p/>
 * You don't *have* to specify your application to listen or USB intents but it might be beneficial for you application,
 * we are enumerating the USB devices during the discovery process which eliminates the need to listen for USB intents.
 * See the Android documentation about USB Host mode for more information
 * <p/>
 * Please note, this is <b>NOT</b> production quality code, error handling has been kept to a minimum to keep the code as clear and concise as possible
 */
class CameraHandler {

    private static final String TAG = "CameraHandler";
    private StreamDataListener streamDataListener;

    public interface StreamDataListener {
        void images(FrameDataHolder dataHolder);

        void images(Bitmap msxBitmap, Bitmap dcBitmap);
    }

    //Discovered FLIR cameras
    LinkedList<Identity> foundCameraIdentities = new LinkedList<>();

    //A FLIR Camera
    private Camera camera;


    public interface DiscoveryStatus {
        void started();

        void stopped();
    }

    public CameraHandler() {
    }

    /**
     * Start discovery of USB and Emulators
     */
    public void startDiscovery(DiscoveryEventListener cameraDiscoveryListener, DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().scan(cameraDiscoveryListener, CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.started();
    }

    /**
     * Stop discovery of USB and Emulators
     */
    public void stopDiscovery(DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().stop(CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.stopped();
    }

    public void connect(Identity identity, ConnectionStatusListener connectionStatusListener) throws IOException {
        camera = new Camera();
        camera.connect(identity, connectionStatusListener, new ConnectParameters());
    }

    public void disconnect() {
        if (camera == null) {
            return;
        }
        if (camera.isGrabbing()) {
            camera.unsubscribeAllStreams();
        }
        camera.disconnect();
    }

    /**
     * Start a stream of {@link ThermalImage}s images from a FLIR ONE or emulator
     */
    public void startStream(StreamDataListener listener) {
        this.streamDataListener = listener;
        camera.subscribeStream(thermalImageStreamListener);
    }

    /**
     * Stop a stream of {@link ThermalImage}s images from a FLIR ONE or emulator
     */
    public void stopStream(ThermalImageStreamListener listener) {
        camera.unsubscribeStream(listener);
    }

    /**
     * Add a found camera to the list of known cameras
     */
    public void add(Identity identity) {
        foundCameraIdentities.add(identity);
    }

    @Nullable
    public Identity get(int i) {
        return foundCameraIdentities.get(i);
    }

    /**
     * Get a read only list of all found cameras
     */
    @Nullable
    public List<Identity> getCameraList() {
        return Collections.unmodifiableList(foundCameraIdentities);
    }

    /**
     * Clear all known network cameras
     */
    public void clear() {
        foundCameraIdentities.clear();
    }

    @Nullable
    public Identity getCppEmulator() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            if (foundCameraIdentity.deviceId.contains("C++ Emulator")) {
                return foundCameraIdentity;
            }
        }
        return null;
    }

    @Nullable
    public Identity getFlirOneEmulator() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            if (foundCameraIdentity.deviceId.contains("EMULATED FLIR ONE")) {
                return foundCameraIdentity;
            }
        }
        return null;
    }

    @Nullable
    public Identity getFlirOne() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            boolean isFlirOneEmulator = foundCameraIdentity.deviceId.contains("EMULATED FLIR ONE");
            boolean isCppEmulator = foundCameraIdentity.deviceId.contains("C++ Emulator");
            if (!isFlirOneEmulator && !isCppEmulator) {
                return foundCameraIdentity;
            }
        }

        return null;
    }

    private void withImage(ThermalImageStreamListener listener, Camera.Consumer<ThermalImage> functionToRun) {
        camera.withImage(listener, functionToRun);
    }


    /**
     * Called whenever there is a new Thermal Image available, should be used in conjunction with {@link Camera.Consumer}
     */
    private final ThermalImageStreamListener thermalImageStreamListener = new ThermalImageStreamListener() {
        @Override
        public void onImageReceived() {
            //Will be called on a non-ui thread
            Log.d(TAG, "onImageReceived(), we got another ThermalImage");
            withImage(this, handleIncomingImage);
        }
    };

    /**
     * Function to process a Thermal Image and update UI
     */
    private final Camera.Consumer<ThermalImage> handleIncomingImage = new Camera.Consumer<ThermalImage>() {
        @Override
        public void accept(ThermalImage thermalImage) {
            Log.d(TAG, "accept() called with: thermalImage = [" + thermalImage.getDescription() + "]");
            //Will be called on a non-ui thread,
            // extract information on the background thread and send the specific information to the UI thread

            //Get a bitmap with only IR data
            Bitmap msxBitmap;
            {
                thermalImage.getFusion().setFusionMode(FusionMode.THERMAL_ONLY);
                Palette p = PaletteManager.getDefaultPalettes().get(0);
                ColorDistribution c = ColorDistribution.TEMPERATURE_LINEAR;
                thermalImage.setPalette(p);
                thermalImage.setColorDistribution(c);
                msxBitmap = BitmapAndroid.createBitmap(thermalImage.getImage()).getBitMap();
                //Log.d(TAG, msxBitmap.toString());
                System.out.println(msxBitmap.getHeight()+ " " +msxBitmap.getWidth());
                System.out.println(thermalImage.getHeight()+ " " +thermalImage.getWidth());
//                Log.d(TAG,thermalImage.getTemperatureUnit().toString());
                //double cutoff = 300;
                Rectangle rectangle = new Rectangle(0, 0, thermalImage.getWidth(), thermalImage.getHeight());
                double[] all_temp = thermalImage.getValues(rectangle);
                //System.out.println(all_temp.length);
                System.out.println("------------------------");
                for(int j=0;j<100;j++)
                {
                    //System.out.println(all_temp[j] + " ");
                }
                System.out.println("\n swap \n");

                for(int k=all_temp.length-100;k<all_temp.length;k++)
                {
                    //System.out.println(all_temp[k] + " ");
                }
                System.out.println("------------------------");

                for (int i = 0; i < all_temp.length; i++) {
                    if (all_temp[i] > MainActivity.GetCutoffTemperature()) {
                        //System.out.println("changed" + i % thermalImage.getWidth() + " "+ i / thermalImage.getHeight() + " "+ all_temp[i]);
                        //Point pt = new Point(i % thermalImage.getWidth(),i / thermalImage.getHeight());
                        //System.out.println(thermalImage.getValueAt(pt) + " "+ i);
                        //if(i % thermalImage.getWidth()%10==0 && i / (thermalImage.getHeight())%10==0 )
                        //{
                            msxBitmap.setPixel(i % thermalImage.getWidth(), i / (thermalImage.getHeight()), -1);
                            //System.out.println(i % thermalImage.getWidth()+" "+i / thermalImage.getHeight());
                        //}



                    }

                }
//                for(int i =0;i<thermalImage.getWidth();i++)
//                {
//                    for(int j=0;j<thermalImage.getHeight();j++)
//                    {
//                        Point p = new Point(i,j);
//                        if(thermalImage.getValueAt(p) > cutoff)
//                        {
//                            msxBitmap.setPixel(i,j,-1);
//                        }
////                        else
////                        {
////                            msxBitmap.setPixel(i,j,255);
////                        }
//                    }
//                    System.out.println();
//                }
//
//
//            }

                //Get a bitmap with the visual image, it might have different dimensions then the bitmap from THERMAL_ONLY
                Bitmap dcBitmap = BitmapAndroid.createBitmap(thermalImage.getFusion().getPhoto()).getBitMap();
                System.out.println(dcBitmap.getWidth()+" "+dcBitmap.getHeight());
                Log.d(TAG, "adding images to cache");
                streamDataListener.images(msxBitmap, dcBitmap);
            }

        }


    };
}

