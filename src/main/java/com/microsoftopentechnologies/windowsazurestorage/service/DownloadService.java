/*
 Copyright 2017 Microsoft Open Technologies, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.microsoftopentechnologies.windowsazurestorage.service;

import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlob;
import com.microsoft.azure.storage.blob.CloudBlobDirectory;
import com.microsoft.azure.storage.blob.ListBlobItem;
import com.microsoft.azure.storage.file.CloudFile;
import com.microsoft.azure.storage.file.FileRequestOptions;
import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsConstants;
import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsUtils;
import com.microsoftopentechnologies.windowsazurestorage.AzureStoragePlugin;
import com.microsoftopentechnologies.windowsazurestorage.exceptions.WAStorageException;
import com.microsoftopentechnologies.windowsazurestorage.helper.Utils;
import com.microsoftopentechnologies.windowsazurestorage.service.model.DownloadServiceData;
import hudson.FilePath;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;

public abstract class DownloadService extends StoragePluginService<DownloadServiceData> {

    protected static final String DOWNLOAD = "Download";
    protected static final String DOWNLOAD_FAILED = "DownloadFailed";

    public DownloadService(DownloadServiceData data) {
        super(data);
    }

    protected int downloadBlobs(Iterable<ListBlobItem> blobItems)
            throws URISyntaxException, StorageException, WAStorageException {
        final DownloadServiceData serviceData = getServiceData();
        int filesDownloaded = 0;
        for (final ListBlobItem blobItem : blobItems) {
            // If the item is a blob, not a virtual directory
            if (blobItem instanceof CloudBlob) {
                // Download the item and save it to a file with the same
                final CloudBlob blob = (CloudBlob) blobItem;

                // Check whether we should download it.
                if (shouldDownload(
                        serviceData.getIncludeFilesPattern(),
                        serviceData.getExcludeFilesPattern(),
                        blob.getName(),
                        true)) {
                    downloadBlob(blob);
                    filesDownloaded++;
                }

            } else if (blobItem instanceof CloudBlobDirectory) {
                final CloudBlobDirectory blobDirectory = (CloudBlobDirectory) blobItem;
                if (shouldDownload(
                        serviceData.getIncludeFilesPattern(),
                        serviceData.getExcludeFilesPattern(),
                        blobDirectory.getPrefix(),
                        false)) {
                    filesDownloaded += downloadBlobs(blobDirectory.listBlobs());
                }
            }
        }
        return filesDownloaded;
    }

    protected void downloadSingleFile(CloudFile cloudFile) throws WAStorageException {
        final DownloadServiceData serviceData = getServiceData();
        String hashedStorageAcc = AppInsightsUtils.hash(cloudFile.getServiceClient().getCredentials().getAccountName());
        try {
            println("Downloading file:" + cloudFile.getUri().toString());
            final FilePath destFile = destinationFilePath(cloudFile.getName());

            final long startTime = System.currentTimeMillis();
            cloudFile.downloadAttributes();
            try (OutputStream fos = destFile.write()) {
                cloudFile.download(fos, null, new FileRequestOptions(),
                        Utils.updateUserAgent(cloudFile.getProperties().getLength()));
            }
            final long endTime = System.currentTimeMillis();
            println(String.format(
                    "blob %s is downloaded to %s in %s",
                    cloudFile.getName(), destFile.getParent(), getTime(endTime - startTime)));

            // send AI event.
            AzureStoragePlugin.sendEvent(AppInsightsConstants.AZURE_FILE_STORAGE, DOWNLOAD,
                    "StorageAccount", hashedStorageAcc,
                    "ContentLength", String.valueOf(cloudFile.getProperties().getLength()));

            if (serviceData.isDeleteFromAzureAfterDownload()) {
                cloudFile.deleteIfExists();
                println("cloud file " + cloudFile.getName() + " is deleted from Azure.");
            }
        } catch (IOException | InterruptedException | StorageException e) {
            AzureStoragePlugin.sendEvent(AppInsightsConstants.AZURE_FILE_STORAGE, DOWNLOAD_FAILED,
                    "StorageAccount", hashedStorageAcc,
                    "ContentLength", String.valueOf(cloudFile.getProperties().getLength()),
                    "Message", e.getMessage());
            throw new WAStorageException(e.getMessage(), e);
        }
    }

    protected void downloadBlob(CloudBlob blob) throws WAStorageException {
        String hashedStorageAcc = AppInsightsUtils.hash(blob.getServiceClient().getCredentials().getAccountName());
        try {
            println("Downloading file:" + blob.getUri().toString());

            final FilePath destFile = destinationFilePath(blob.getName());
            final long startTime = System.currentTimeMillis();
            blob.downloadAttributes();
            try (OutputStream fos = destFile.write()) {
                blob.download(fos, null, getBlobRequestOptions(),
                        Utils.updateUserAgent(blob.getProperties().getLength()));
            }
            final long endTime = System.currentTimeMillis();
            println(String.format("blob %s is downloaded to %s in %s",
                    blob.getName(), destFile.getParent(), getTime(endTime - startTime)));

            // send AI event.
            AzureStoragePlugin.sendEvent(AppInsightsConstants.AZURE_BLOB_STORAGE, DOWNLOAD,
                    "StorageAccount", hashedStorageAcc,
                    "ContentLength", String.valueOf(blob.getProperties().getLength()));

            if (getServiceData().isDeleteFromAzureAfterDownload()) {
                blob.deleteIfExists();
                println("blob " + blob.getName() + " is deleted from Azure.");
            }
        } catch (IOException | StorageException | InterruptedException e) {
            AzureStoragePlugin.sendEvent(AppInsightsConstants.AZURE_BLOB_STORAGE, DOWNLOAD_FAILED,
                    "StorageAccount", hashedStorageAcc,
                    "ContentLength", String.valueOf(blob.getProperties().getLength()),
                    "Message", e.getMessage());
            throw new WAStorageException(e.getMessage(), e);
        }
    }

    protected boolean shouldDownload(
            String includePattern,
            String excludePattern,
            String blobName,
            boolean isFullPath) {
        String[] includePatterns = includePattern.split(FP_SEPARATOR);
        String[] excludePatterns = null;

        if (excludePattern != null) {
            excludePatterns = excludePattern.split(FP_SEPARATOR);
        }

        return blobPathMatches(blobName, includePatterns, excludePatterns, isFullPath);
    }

    private FilePath destinationFilePath(String name) {
        final DownloadServiceData serviceData = getServiceData();
        final FilePath downloadDir = serviceData.getDownloadDir();
        FilePath downloadFile = new FilePath(downloadDir, name);

        // That filepath will contain all the directories and explicit virtual
        // paths, so if the user wanted it flattened, grab just the file name and
        // recreate the file path
        if (serviceData.isFlattenDirectories()) {
            downloadFile = new FilePath(downloadDir, downloadFile.getName());
        }

        return downloadFile;
    }

    private boolean blobPathMatches(
            String path,
            String[] includePatterns,
            String[] excludePatterns,
            boolean isFullPath) {
        if (!isFullPath) {
            // If we don't have a full path, we can't check for exclusions
            // yet.  Consider include: **/*, exclude **/foo.txt.  Both would match
            // any dir.
            return isPotentialMatch(path, includePatterns);
        } else {
            return isExactMatch(path, includePatterns)
                    && (excludePatterns == null || !isExactMatch(path, excludePatterns));
        }
    }

    /**
     * Determines whether the path is a potential match to any of the provided
     * patterns.
     *
     * @param path
     * @param patterns
     * @return
     */
    private boolean isPotentialMatch(String path, String[] patterns) {
        AntPathMatcher matcher = new AntPathMatcher();
        for (String pattern : patterns) {
            if (matcher.matchStart(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether the path is an exact match to any of the provided
     * patterns.
     *
     * @param path
     * @param patterns
     * @return
     */
    private boolean isExactMatch(String path, String[] patterns) {
        AntPathMatcher matcher = new AntPathMatcher();
        for (String pattern : patterns) {
            if (matcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}
