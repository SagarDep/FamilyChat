package com.lib.shortvideo.videoview.recorder;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import com.lib.shortvideo.videoview.views.CameraPreviewView;

import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameFilter;
import org.bytedeco.javacv.FrameRecorder;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;


/**
 * 仿微信录像机
 */
public class WXLikeVideoRecorder implements Camera.PreviewCallback, CameraPreviewView.PreviewEventListener
{

    private static final String TAG = "WXLikeVideoRecorder";

    // 帧率
    private static final int FRAME_RATE = 30;
    // 声音采样率
    private static final int SAMPLE_AUDIO_RATE_IN_HZ = 44100;
    private final Context mContext;

    // 最长录制时间
    private long MAX_RECORD_TIME = 6000;
    // 输出文件目录,默认sd卡
    private String mFolder = Environment.getExternalStorageDirectory().getAbsolutePath();
    // 输出文件路径
    private String strFinalPath;
    // 图片帧宽、高
    private int imageWidth = 320;//默认值320
    private int imageHeight = 240;//默认值240
    // 输出视频宽、高
    private int outputWidth = 320;//默认值320
    private int outputHeight = 240;//默认值240

    /* audio data getting thread */
    private AudioRecord audioRecord;
    private AudioRecordRunnable audioRecordRunnable;
    private Thread audioThread;
    volatile boolean runAudioThread = true;

    private volatile FFmpegFrameRecorder recorder;

    /**
     * 录制开始时间
     */
    private long startTime;

    /**
     * 录制停止时间
     */
    private long stopTime;

    private boolean recording;

    /* The number of seconds in the continuous record loop (or 0 to disable loop). */
    final int RECORD_LENGTH = /*6*/0;
    Frame[] images;
    long[] timestamps;
    ShortBuffer[] samples;
    int imagesIndex, samplesIndex;
    private Frame yuvImage = null;

    // 图片帧过滤器
    private FFmpegFrameFilter mFrameFilter;
    // 相机预览视图
    private CameraPreviewView mCameraPreviewView;

    /**
     * 帧数据处理配置
     */
    private String mFilters;

    public WXLikeVideoRecorder(Context context, String folder)
    {
        mContext = context;
        mFolder = folder;
    }

    public void setMaxRecordTime(long maxRecordTime)
    {
        this.MAX_RECORD_TIME = maxRecordTime;
    }

    public boolean isRecording()
    {
        return recording;
    }

    /**
     * 设置图片帧的大小
     *
     * @param width
     * @param height
     */
    public void setFrameSize(int width, int height)
    {
        imageWidth = width;
        imageHeight = height;
    }

    /**
     * 设置输出视频大小
     *
     * @param width
     * @param height
     */
    public void setOutputSize(int width, int height)
    {
        outputWidth = width;
        outputHeight = height;
    }

    /**
     * 获取开始时间
     */
    public long getStartTime()
    {
        return startTime;
    }

    /**
     * 获取停止时间
     */
    public long getStopTime()
    {
        return stopTime;
    }

    //---------------------------------------
    // initialize ffmpeg_recorder
    //---------------------------------------
    private void initRecorder()
    {
        if (RECORD_LENGTH > 0)
        {
            imagesIndex = 0;
            images = new Frame[RECORD_LENGTH * FRAME_RATE];
            timestamps = new long[images.length];
            for (int i = 0; i < images.length; i++)
            {
                images[i] = new Frame(imageWidth, imageHeight, Frame.DEPTH_UBYTE, 2);
                timestamps[i] = -1;
            }
        } else if (yuvImage == null)
        {
            yuvImage = new Frame(imageWidth, imageHeight, Frame.DEPTH_UBYTE, 2);
        }
        RecorderParameters recorderParameters = RecorderParameters.getRecorderParameter(Constants.RESOLUTION_HIGH_VALUE);
        //        strFinalPath = FileUtil.createFilePath(mFolder, null, Long.toString(System.currentTimeMillis()));
        strFinalPath = createFilePath();
        // 初始化时设置录像机的目标视频大小
        recorder = new FFmpegFrameRecorder(strFinalPath, outputWidth, outputHeight, 1);
        recorder.setFormat(recorderParameters.getVideoOutputFormat());
        recorder.setSampleRate(SAMPLE_AUDIO_RATE_IN_HZ);
        recorder.setFrameRate(FRAME_RATE);
        audioRecordRunnable = new AudioRecordRunnable();
        audioThread = new Thread(audioRecordRunnable);
        runAudioThread = true;
    }

    private String createFilePath()
    {
        File floderFile = new File(mFolder);
        if (!floderFile.exists())
            floderFile.mkdirs();

        return new File(floderFile, createFileName()).getAbsolutePath();
    }

    //创建录音文件的名字
    private String createFileName()
    {
        return new StringBuffer()
                .append(Constants.FILE_START_NAME)
                .append(System.currentTimeMillis())
                .append(Constants.VIDEO_EXTENSION)
                .toString();
    }

    /**
     * 设置帧图像数据处理参数
     */
    public void setFilters(String filters)
    {
        mFilters = filters;
    }

    /**
     * 生成处理配置
     *
     * @param w         裁切宽度
     * @param h         裁切高度
     * @param x         裁切起始x坐标
     * @param y         裁切起始y坐标
     * @param transpose 图像旋转参数
     * @return 帧图像数据处理参数
     */
    public static String generateFilters(int w, int h, int x, int y, String transpose)
    {
        return String.format("crop=w=%d:h=%d:x=%d:y=%d,transpose=%s", w, h, x, y, transpose);
    }

    /**
     * 初始化帧过滤器
     */
    private void initFrameFilter()
    {
        if (TextUtils.isEmpty(mFilters))
        {
            mFilters = generateFilters((int) (1f * outputHeight / outputWidth * imageHeight), imageHeight, 0, 0, "clock");
        }
        mFrameFilter = new FFmpegFrameFilter(mFilters, imageWidth, imageHeight);
        mFrameFilter.setPixelFormat(org.bytedeco.javacpp.avutil.AV_PIX_FMT_NV21); // default camera format on Android
    }

    /**
     * 释放帧过滤器
     */
    private void releaseFrameFilter()
    {
        if (null != mFrameFilter)
        {
            try
            {
                mFrameFilter.release();
            } catch (FrameFilter.Exception e)
            {
                e.printStackTrace();
            }
        }
        mFrameFilter = null;
    }

    /**
     * 获取视频文件路径
     */
    public String getFilePath()
    {
        return strFinalPath;
    }

    /**
     * 开始录制
     */
    public boolean startRecording()
    {
        boolean started = true;
        initRecorder();
        initFrameFilter();
        try
        {
            recorder.start();
            mFrameFilter.start();
            startTime = System.currentTimeMillis();
            recording = true;
            audioThread.start();
        } catch (Exception e)
        {
            started = false;
            Log.e(TAG, "startRecording fail:" + e.toString());
        }

        return started;
    }

    public void stopRecording()
    {
        if (!recording)
            return;

        stopTime = System.currentTimeMillis();

        runAudioThread = false;
        try
        {
            audioThread.join();
        } catch (InterruptedException e)
        {
            Log.e(TAG, "stopRecording fail:" + e.toString());
        }
        audioRecordRunnable = null;
        audioThread = null;

        if (recorder != null && recording)
        {
            if (RECORD_LENGTH > 0)
            {
                try
                {
                    int firstIndex = imagesIndex % samples.length;
                    int lastIndex = (imagesIndex - 1) % images.length;
                    if (imagesIndex <= images.length)
                    {
                        firstIndex = 0;
                        lastIndex = imagesIndex - 1;
                    }
                    if ((startTime = timestamps[lastIndex] - RECORD_LENGTH * 1000000L) < 0)
                    {
                        startTime = 0;
                    }
                    if (lastIndex < firstIndex)
                    {
                        lastIndex += images.length;
                    }
                    for (int i = firstIndex; i <= lastIndex; i++)
                    {
                        long t = timestamps[i % timestamps.length] - startTime;
                        if (t >= 0)
                        {
                            if (t > recorder.getTimestamp())
                            {
                                recorder.setTimestamp(t);
                            }
                            recordFrame(images[i % images.length]);
                        }
                    }

                    firstIndex = samplesIndex % samples.length;
                    lastIndex = (samplesIndex - 1) % samples.length;
                    if (samplesIndex <= samples.length)
                    {
                        firstIndex = 0;
                        lastIndex = samplesIndex - 1;
                    }
                    if (lastIndex < firstIndex)
                    {
                        lastIndex += samples.length;
                    }
                    for (int i = firstIndex; i <= lastIndex; i++)
                    {
                        recorder.recordSamples(samples[i % samples.length]);
                    }
                } catch (Exception e)
                {
                    Log.v(TAG, e.getMessage());
                }
            }

            recording = false;
            Log.v(TAG, "Finishing recording, calling stop and release on recorder");
            try
            {
                recorder.stop();
                recorder.release();
            } catch (FFmpegFrameRecorder.Exception e)
            {
                e.printStackTrace();
            }
            recorder = null;

            // 释放帧过滤器
            releaseFrameFilter();
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera)
    {
        try
        {
            if (RECORD_LENGTH > 0)
            {
                int i = imagesIndex++ % images.length;
                yuvImage = images[i];
                timestamps[i] = 1000 * (System.currentTimeMillis() - startTime);
            }
            /* get video data */
            if (yuvImage != null && recording)
            {
                ((ByteBuffer) yuvImage.image[0].position(0)).put(data);

                if (RECORD_LENGTH <= 0)
                {
                    try
                    {
                        long pastTime = System.currentTimeMillis() - startTime;
                        if (pastTime >= MAX_RECORD_TIME)
                        {
                            stopRecording();
                            return;
                        }
                        long t = 1000 * pastTime;
                        if (t > recorder.getTimestamp())
                        {
                            recorder.setTimestamp(t);
                        }
                        recordFrame(yuvImage);
                    } catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        } finally
        {
            camera.addCallbackBuffer(data);
        }
    }

    /**
     * 录制帧
     *
     * @throws FrameRecorder.Exception
     */
    private void recordFrame(Frame frame) throws FrameRecorder.Exception, FrameFilter.Exception
    {
        mFrameFilter.push(frame);
        Frame filteredFrame;
        while ((filteredFrame = mFrameFilter.pull()) != null)
        {
            recorder.record(filteredFrame);
        }
    }

    /**
     * 设置相机预览视图
     *
     * @param cameraPreviewView
     */
    public void setCameraPreviewView(CameraPreviewView cameraPreviewView)
    {
        mCameraPreviewView = cameraPreviewView;
        mCameraPreviewView.addPreviewEventListener(this);
        mCameraPreviewView.setViewWHRatio(1f * outputWidth / outputHeight);
    }

    @Override
    public void onPrePreviewStart()
    {
        Camera camera = mCameraPreviewView.getCamera();
        Camera.Parameters parameters = camera.getParameters();
        Camera.Size size = parameters.getPreviewSize();
        // 设置Recorder处理的的图像帧大小
        setFrameSize(size.width, size.height);

        camera.setPreviewCallbackWithBuffer(this);
        camera.addCallbackBuffer(new byte[size.width * size.height * ImageFormat.getBitsPerPixel(parameters.getPreviewFormat()) / 8]);
    }

    @Override
    public void onPreviewStarted()
    {
    }

    @Override
    public void onPreviewFailed()
    {
    }

    @Override
    public void onAutoFocusComplete(boolean success)
    {
    }

    //---------------------------------------------
    // audio thread, gets and encodes audio data
    //---------------------------------------------
    class AudioRecordRunnable implements Runnable
    {

        @Override
        public void run()
        {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            // Audio
            int bufferSize;
            ShortBuffer audioData;
            int bufferReadResult;

            bufferSize = AudioRecord.getMinBufferSize(SAMPLE_AUDIO_RATE_IN_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_AUDIO_RATE_IN_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            if (RECORD_LENGTH > 0)
            {
                samplesIndex = 0;
                samples = new ShortBuffer[RECORD_LENGTH * SAMPLE_AUDIO_RATE_IN_HZ * 2 / bufferSize + 1];
                for (int i = 0; i < samples.length; i++)
                {
                    samples[i] = ShortBuffer.allocate(bufferSize);
                }
            } else
            {
                audioData = ShortBuffer.allocate(bufferSize);
            }

            Log.d(TAG, "audioRecord.startRecording()");
            audioRecord.startRecording();

            /* ffmpeg_audio encoding loop */
            while (runAudioThread)
            {
                if (RECORD_LENGTH > 0)
                {
                    audioData = samples[samplesIndex++ % samples.length];
                    audioData.position(0).limit(0);
                }
                //Log.v(LOG_TAG,"recording? " + recording);
                bufferReadResult = audioRecord.read(audioData.array(), 0, audioData.capacity());
                audioData.limit(bufferReadResult);
                if (bufferReadResult > 0)
                {
                    Log.v(TAG, "bufferReadResult: " + bufferReadResult);
                    // If "recording" isn't true when start this thread, it never get's set according to this if statement...!!!
                    // Why?  Good question...
                    if (recording)
                    {
                        if (RECORD_LENGTH <= 0)
                            try
                            {
                                recorder.recordSamples(audioData);
                                //Log.v(LOG_TAG,"recording " + 1024*i + " to " + 1024*i+1024);
                            } catch (FFmpegFrameRecorder.Exception e)
                            {
                                Log.v(TAG, e.getMessage());
                                e.printStackTrace();
                            }
                    }
                }
            }
            Log.v(TAG, "AudioThread Finished, release audioRecord");

            /* encoding finish, release recorder */
            if (audioRecord != null)
            {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
                Log.v(TAG, "audioRecord released");
            }
        }
    }

    //重置参数
    public void resetParams()
    {
        recording = false;
        strFinalPath = null;
        startTime = 0;
        stopTime = 0;
    }
}
