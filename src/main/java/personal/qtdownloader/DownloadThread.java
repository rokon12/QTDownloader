/**
 * Class: Download.java
 *
 * @author quan
 *
 */
package personal.qtdownloader;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.time.Duration;
import java.time.Instant;
import static personal.qtdownloader.Main.PROGRAM_TEMP_DIR;

/**
 *
 * @author quan
 */
public class DownloadThread implements Runnable {

    private Thread mThread;
    private long mStartByte;
    private long mEndByte;
    private long mPartSize;
    private final boolean mResume;
    private int mPart;
    private URL mUrl;
    private long mDownloadedSize;
    private long mAlreadyDownloaded;

    private final String mFileName;

    private final Progress mProgress;

    /**
     * Construct a download object with the given URL and byte range to
     * downloads
     *
     * @param url The URL to download from.
     * @param startByte The starting byte.
     * @param endByte The end byte.
     * @param partSize The size of the part needed to be downloaded.
     * @param part The part of the file being downloaded.
     * @param progress
     * @param resume Whether to resume the download or not.
     */
    public DownloadThread(URL url, long startByte, long endByte, long partSize,
            int part, Progress progress, boolean resume) {
        if (startByte >= endByte) {
            throw new RuntimeException("The start byte cannot be larger than "
                    + "the end byte!");
        }

        mStartByte = startByte;
        mEndByte = endByte;
        mPartSize = partSize;
        mResume = resume;
        mUrl = url;
        mPart = part;
        mDownloadedSize = 0;
        mAlreadyDownloaded = 0;

        // Get the file name.
        mFileName = Main.PROGRAM_TEMP_DIR + "." + (new File(mUrl.toExternalForm()).getName()
                + ".part" + mPart);

        // Initialize the thread
        mThread = new Thread(this, "Part #" + part);

        mProgress = progress;

        // If resume a download then set the start byte
        if (mResume) {
            try (RandomAccessFile partFile = new RandomAccessFile(mFileName, "rw")) {
                mAlreadyDownloaded = partFile.length();
                mStartByte += mAlreadyDownloaded;
                mDownloadedSize += mAlreadyDownloaded;
            } catch (IOException ex) {
                // If cannot open the part file, leave the start byte as it is
                // to download the entire part again.
            }
        }
    }

    /**
     * Start the thread to download.
     */
    public void startDownload() {
        mThread.start();
    }

    /**
     * Wait for the thread to finish.
     *
     * @throws java.lang.InterruptedException If join() failed.
     */
    public void joinThread() throws InterruptedException {
        mThread.join();
    }

    /**
     * Get the HTTP Connection with the download URL.
     *
     * @return The HTTP connection with the download URL.
     * @throws java.net.MalformedURLException if the given URL is invalid.
     * @throws IOException if failed to connect to the given URL.
     */
    public HttpURLConnection getHttpConnection() throws IOException {
        // Connect to the URL
        HttpURLConnection conn = (HttpURLConnection) mUrl.openConnection();

        String downloadRange = "bytes=" + mStartByte + "-" + mEndByte;
        conn.setRequestProperty("Range", downloadRange);
        conn.connect();

        // Return the connection.
        return conn;
    }

    /**
     * Write the data from the given connection to file.
     *
     * @param conn
     * @throws java.io.IOException
     */
    public void downloadToFile(HttpURLConnection conn) throws IOException {
        // Get the input stream.
        InputStream is = conn.getInputStream();

        // Size of the chunk of data to be downloaded and written to the 
        // output file at a time.
        int chunkSize = (int) Math.pow(2, 13); // 8KB

        try (DataInputStream dataIn = new DataInputStream(is)) {
            // Get the file's length.
            long contentLength = conn.getContentLengthLong();
            contentLength += mAlreadyDownloaded;

            // Read a chunk of given size at time and write the actual amount
            // of bytes read to the output file.
            byte[] dataArray = new byte[chunkSize];
            int result;

            // A boolean variable to determine whether to overwrite the output
            // file or not.
            // After the first time the writeToFile function is called, it will
            // be changed to false, which means the next times the data is 
            // written it is appended to the file.
            boolean overwrite = true;
            if (mResume) {
                overwrite = false;
            }

            synchronized (mProgress) {
                mProgress.downloadedCount += mDownloadedSize;
                mProgress.notifyAll();
            }

            // While the total downloaded size is still smaller than the 
            // content length from the connection, keep reading data.
            while (mDownloadedSize < contentLength) {
                Instant start = mProgress.startDownloadTimeStamp;
                result = dataIn.read(dataArray, 0, chunkSize);
                Instant stop = Instant.now();
                long time = Duration.between(stop, start).getNano();

                if (result == -1) {
                    break;
                }

                mDownloadedSize += result;
                writeToFile(dataArray, result, overwrite);
                overwrite = false;

                synchronized (mProgress) {
                    mProgress.downloadedCount += result;
                    mProgress.time += time;
                    mProgress.sizeChange += result;
                    mProgress.percentageCount++;
                    
                    mProgress.updateProgressBar();
                    if (mProgress.percentageCount == 1) {
                        mProgress.time = 0;
                        mProgress.sizeChange = 0;
                        mProgress.percentageCount = 0;
                    }

                    mProgress.notifyAll();
                }
            }
        }
    }

    /**
     * Write the given data to the download part file.
     *
     * @param bytes Byte array of data to write to the download part file.
     * @param bytesToWrite Number of bytes in the byte array to be written.
     * @param overwrite True if the file is to be overwritten by the given data.
     * @throws IOException if failed to write to file.
     */
    public void writeToFile(byte[] bytes, int bytesToWrite, boolean overwrite) throws IOException {
        try (FileOutputStream fout = new FileOutputStream(mFileName, !overwrite)) {
            // Write to the output file using FileChannel.
            FileChannel outChannel = fout.getChannel();

            // Wrap the given byte array in a ByteBuffer.
            ByteBuffer data = ByteBuffer.wrap(bytes, 0, bytesToWrite);

            // Write the data.
            outChannel.write(data);
        }
    }

    /**
     * Gets the downloaded size.
     *
     * @return The downloaded size.
     */
    public long getDownloadedSize() {
        return mDownloadedSize;
    }

    /**
     * Gets the size of the current part that needs to be downloaded.
     *
     * @return The size of the current part needed to be downloaded.
     */
    public long getPartSize() {
        return mPartSize;
    }

    @Override
    public void run() {
        try {
            // Connect to the URL
            HttpURLConnection conn = getHttpConnection();

            // Download to file
            downloadToFile(conn);
        } catch (IOException ex) {
            synchronized (mProgress) {
                mProgress.ex = ex;
                mProgress.notifyAll();
            }
        }
    }

}
