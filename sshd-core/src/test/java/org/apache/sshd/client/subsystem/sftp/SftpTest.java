/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.client.subsystem.sftp;

import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FX_FILE_ALREADY_EXISTS;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FX_NO_SUCH_FILE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.S_IRUSR;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.S_IWUSR;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.client.SftpException;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.subsystem.sftp.SftpClient.CloseableHandle;
import org.apache.sshd.client.subsystem.sftp.extensions.BuiltinSftpClientExtensions;
import org.apache.sshd.client.subsystem.sftp.extensions.CopyFileExtension;
import org.apache.sshd.client.subsystem.sftp.extensions.MD5FileExtension;
import org.apache.sshd.client.subsystem.sftp.extensions.MD5HandleExtension;
import org.apache.sshd.client.subsystem.sftp.extensions.SftpClientExtension;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.digest.Digest;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.file.root.RootedFileSystemProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.subsystem.sftp.SftpConstants;
import org.apache.sshd.common.subsystem.sftp.extensions.ParserUtils;
import org.apache.sshd.common.subsystem.sftp.extensions.Supported2Parser.Supported2;
import org.apache.sshd.common.subsystem.sftp.extensions.SupportedParser.Supported;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.OsUtils;
import org.apache.sshd.common.util.buffer.BufferUtils;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.io.IoUtils;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.ScpCommandFactory;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystem;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystemFactory;
import org.apache.sshd.util.BaseTestSupport;
import org.apache.sshd.util.BogusPasswordAuthenticator;
import org.apache.sshd.util.EchoShellFactory;
import org.apache.sshd.util.JSchLogger;
import org.apache.sshd.util.SimpleUserInfo;
import org.apache.sshd.util.Utils;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SftpTest extends BaseTestSupport {

    private SshServer sshd;
    private int port;
    private com.jcraft.jsch.Session session;
    private final FileSystemFactory fileSystemFactory;

    public SftpTest() throws IOException {
        Path targetPath = detectTargetFolder().toPath();
        Path parentPath = targetPath.getParent();
        final FileSystem fileSystem = new RootedFileSystemProvider().newFileSystem(parentPath, Collections.<String,Object>emptyMap());
        fileSystemFactory = new FileSystemFactory() {
            @Override
            public FileSystem createFileSystem(Session session) throws IOException {
                return fileSystem;
            }
        };
    }

    @Before
    public void setUp() throws Exception {
        sshd = SshServer.setUpDefaultServer();
        sshd.setKeyPairProvider(Utils.createTestHostKeyProvider());
        sshd.setSubsystemFactories(Arrays.<NamedFactory<Command>>asList(new SftpSubsystemFactory()));
        sshd.setCommandFactory(new ScpCommandFactory());
        sshd.setShellFactory(new EchoShellFactory());
        sshd.setPasswordAuthenticator(BogusPasswordAuthenticator.INSTANCE);
        sshd.setFileSystemFactory(fileSystemFactory);
        sshd.start();
        port = sshd.getPort();

        JSchLogger.init();
        JSch sch = new JSch();
        session = sch.getSession("sshd", "localhost", port);
        session.setUserInfo(new SimpleUserInfo("sshd"));
        session.connect();
    }

    @After
    public void tearDown() throws Exception {
        if (session != null) {
            session.disconnect();
        }
        
        if (sshd != null) {
            sshd.stop(true);
        }
    }

    @Test
    @Ignore
    public void testExternal() throws Exception {
        System.out.println("SFTP subsystem available on port " + port);
        Thread.sleep(5 * 60000);
    }

    @Test
    public void testOpen() throws Exception {
        try(SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            
            try (ClientSession session = client.connect(getCurrentTestName(), "localhost", port).verify(7L, TimeUnit.SECONDS).getSession()) {
                session.addPasswordIdentity(getCurrentTestName());
                session.auth().verify(5L, TimeUnit.SECONDS);

                Path targetPath = detectTargetFolder().toPath();
                Path parentPath = targetPath.getParent();
                Path lclSftp = Utils.resolve(targetPath, SftpConstants.SFTP_SUBSYSTEM_NAME, getClass().getSimpleName(), getCurrentTestName());
                Path clientFolder = lclSftp.resolve("client");
                Path testFile = clientFolder.resolve(getCurrentTestName() + ".txt");
                String file = Utils.resolveRelativeRemotePath(parentPath, testFile);

                File javaFile = testFile.toFile();
                assertHierarchyTargetFolderExists(javaFile.getParentFile());
                javaFile.createNewFile();
                javaFile.setWritable(false, false);
                javaFile.setReadable(false, false);
        
                try (SftpClient sftp = session.createSftpClient()) {
                    boolean	isWindows = OsUtils.isWin32();
            
                    try(SftpClient.CloseableHandle h = sftp.open(file /* no mode == read */)) {
                        // NOTE: on Windows files are always readable
                        // see https://svn.apache.org/repos/asf/harmony/enhanced/java/branches/java6/classlib/modules/luni/src/test/api/windows/org/apache/harmony/luni/tests/java/io/WinFileTest.java
                        assertTrue("Empty read should have failed on " + file, isWindows);
                    } catch (IOException e) {
                        if (isWindows) {
                            throw e;
                        }
                    }
            
                    try(SftpClient.CloseableHandle h = sftp.open(file, EnumSet.of(SftpClient.OpenMode.Write))) {
                        fail("Empty write should have failed on " + file);
                    } catch (IOException e) {
                        // ok
                    }
    
                    try(SftpClient.CloseableHandle h = sftp.open(file, EnumSet.of(SftpClient.OpenMode.Truncate))) {
                        // NOTE: on Windows files are always readable
                        assertTrue("Empty truncate should have failed on " + file, isWindows);
                    } catch (IOException e) {
                        // ok
                    }
    
                    // NOTE: on Windows files are always readable
                    int	perms=sftp.stat(file).perms;
                    int	permsMask=S_IWUSR | (isWindows ? 0 : S_IRUSR);
                    assertEquals("Mismatched permissions for " + file + ": 0x" + Integer.toHexString(perms), 0, (perms & permsMask));
            
                    javaFile.setWritable(true, false);
            
                    try(SftpClient.CloseableHandle h = sftp.open(file, EnumSet.of(SftpClient.OpenMode.Truncate, SftpClient.OpenMode.Write))) {
                        // OK should succeed
                        assertTrue("Handle not marked as open for file=" + file, h.isOpen());
                    }
            
                    byte[] d = "0123456789\n".getBytes();
                    try(SftpClient.CloseableHandle h = sftp.open(file, EnumSet.of(SftpClient.OpenMode.Write))) {
                        sftp.write(h, 0, d, 0, d.length);
                        sftp.write(h, d.length, d, 0, d.length);
                    }

                    try(SftpClient.CloseableHandle h = sftp.open(file, EnumSet.of(SftpClient.OpenMode.Write))) {
                        sftp.write(h, d.length * 2, d, 0, d.length);
                    }

                    try(SftpClient.CloseableHandle h = sftp.open(file, EnumSet.of(SftpClient.OpenMode.Write))) {
                        sftp.write(h, 3, "-".getBytes(), 0, 1);
                    }

                    try(SftpClient.CloseableHandle h = sftp.open(file /* no mode == read */)) {
                        // NOTE: on Windows files are always readable
                        assertTrue("Data read should have failed on " + file, isWindows);
                    } catch (IOException e) {
                        if (isWindows) {
                            throw e;
                        }
                    }
            
                    javaFile.setReadable(true, false);
            
                    byte[] buf = new byte[3];
                    try(SftpClient.CloseableHandle h = sftp.open(file /* no mode == read */)) {
                        int l = sftp.read(h, 2l, buf, 0, 3);
                        assertEquals("Mismatched read data", "2-4", new String(buf, 0, l));
                    }
                }
            } finally {
                client.stop();
            }
        }
    }

    @Test
    public void testClient() throws Exception {
        try(SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = client.connect(getCurrentTestName(), "localhost", port).verify(7L, TimeUnit.SECONDS).getSession()) {
                session.addPasswordIdentity(getCurrentTestName());
                session.auth().verify(5L, TimeUnit.SECONDS);

                try (SftpClient sftp = session.createSftpClient()) {
                    testClient(sftp);
                }
            } finally {
                client.stop();
            }
        }
    }

    /**
     * this test is meant to test out write's logic, to ensure that internal chunking (based on Buffer.MAX_LEN) is
     * functioning properly. To do this, we write a variety of file sizes, both smaller and larger than Buffer.MAX_LEN.
     * in addition, this test ensures that improper arguments passed in get caught with an IllegalArgumentException
     * @throws Exception upon any uncaught exception or failure
     */
    @Test
    public void testWriteChunking() throws Exception {
        try(SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            
            try (ClientSession session = client.connect(getCurrentTestName(), "localhost", port).verify(7L, TimeUnit.SECONDS).getSession()) {
                session.addPasswordIdentity(getCurrentTestName());
                session.auth().verify(5L, TimeUnit.SECONDS);
        
                Path targetPath = detectTargetFolder().toPath();
                Path lclSftp = Utils.resolve(targetPath, SftpConstants.SFTP_SUBSYSTEM_NAME, getClass().getSimpleName(), getCurrentTestName());
                Utils.deleteRecursive(lclSftp);
                Files.createDirectories(lclSftp);

                Path parentPath = targetPath.getParent();
                Path clientFolder = lclSftp.resolve("client");
                String dir = Utils.resolveRelativeRemotePath(parentPath, clientFolder);
        
                try(SftpClient sftp = session.createSftpClient()) {
                    sftp.mkdir(dir);
            
                    uploadAndVerifyFile(sftp, clientFolder, dir, 0, "emptyFile.txt");
                    uploadAndVerifyFile(sftp, clientFolder, dir, 1000, "smallFile.txt");
                    uploadAndVerifyFile(sftp, clientFolder, dir, ByteArrayBuffer.MAX_LEN - 1, "bufferMaxLenMinusOneFile.txt");
                    uploadAndVerifyFile(sftp, clientFolder, dir, ByteArrayBuffer.MAX_LEN, "bufferMaxLenFile.txt");
                    // were chunking not implemented, these would fail. these sizes should invoke our internal chunking mechanism
                    uploadAndVerifyFile(sftp, clientFolder, dir, ByteArrayBuffer.MAX_LEN + 1, "bufferMaxLenPlusOneFile.txt");
                    uploadAndVerifyFile(sftp, clientFolder, dir, (int)(1.5 * ByteArrayBuffer.MAX_LEN), "1point5BufferMaxLenFile.txt");
                    uploadAndVerifyFile(sftp, clientFolder, dir, (2 * ByteArrayBuffer.MAX_LEN) - 1, "2TimesBufferMaxLenMinusOneFile.txt");
                    uploadAndVerifyFile(sftp, clientFolder, dir, 2 * ByteArrayBuffer.MAX_LEN, "2TimesBufferMaxLenFile.txt");
                    uploadAndVerifyFile(sftp, clientFolder, dir, (2 * ByteArrayBuffer.MAX_LEN) + 1, "2TimesBufferMaxLenPlusOneFile.txt");
                    uploadAndVerifyFile(sftp, clientFolder, dir, 200000, "largerFile.txt");
            
                    // test erroneous calls that check for negative values
                    Path invalidPath = clientFolder.resolve(getCurrentTestName() + "-invalid");
                    testInvalidParams(sftp, invalidPath, Utils.resolveRelativeRemotePath(parentPath, invalidPath));
            
                    // cleanup
                    sftp.rmdir(dir);
                }
            } finally {
                client.stop();
            }
        }
    }

    private void testInvalidParams(SftpClient sftp, Path file, String filePath) throws Exception {
        // generate random file and upload it
        String randomData = randomString(5);
        byte[] randomBytes = randomData.getBytes();
        try(SftpClient.CloseableHandle handle = sftp.open(filePath, EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create))) {
            try {
                sftp.write(handle, -1, randomBytes, 0, 0);
                fail("should not have been able to write file with invalid file offset for " + filePath);
            } catch (IllegalArgumentException e) {
                // expected
            }
            try {
                sftp.write(handle, 0, randomBytes, -1, 0);
                fail("should not have been able to write file with invalid source offset for " + filePath);
            } catch (IllegalArgumentException e) {
                // expected
            }
            try {
                sftp.write(handle, 0, randomBytes, 0, -1);
                fail("should not have been able to write file with invalid length for " + filePath);
            } catch (IllegalArgumentException e) {
                // expected
            }
            try {
                sftp.write(handle, 0, randomBytes, 0, randomBytes.length + 1);
                fail("should not have been able to write file with length bigger than array itself (no offset) for " + filePath);
            } catch (IllegalArgumentException e) {
                // expected
            }
            try {
                sftp.write(handle, 0, randomBytes, randomBytes.length, 1);
                fail("should not have been able to write file with length bigger than array itself (with offset) for " + filePath);
            } catch (IllegalArgumentException e) {
                // expected
            }
        }

        sftp.remove(filePath);
        assertFalse("File should not be there: " + file.toString(), Files.exists(file));
    }

    private void uploadAndVerifyFile(SftpClient sftp, Path clientFolder, String remoteDir, int size, String filename) throws Exception {
        // generate random file and upload it
        String remotePath = remoteDir + "/" + filename;
        String randomData = randomString(size);
        try(SftpClient.CloseableHandle handle = sftp.open(remotePath, EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create))) {
            sftp.write(handle, 0, randomData.getBytes(), 0, randomData.length());
        }

        // verify results
        Path resultPath = clientFolder.resolve(filename);
        assertTrue("File should exist on disk: " + resultPath, Files.exists(resultPath));
        assertTrue("Mismatched file contents: " + resultPath, randomData.equals(readFile(remotePath)));

        // cleanup
        sftp.remove(remotePath);
        assertFalse("File should have been removed: " + resultPath, Files.exists(resultPath));
    }

    @Test
    public void testSftp() throws Exception {
        String d = getCurrentTestName() + "\n";

        Path targetPath = detectTargetFolder().toPath();
        Path lclSftp = Utils.resolve(targetPath, SftpConstants.SFTP_SUBSYSTEM_NAME, getClass().getSimpleName(), getCurrentTestName());
        Utils.deleteRecursive(lclSftp);
        Files.createDirectories(lclSftp);

        Path target = lclSftp.resolve(getCurrentTestName() + ".txt");
        String remotePath = Utils.resolveRelativeRemotePath(targetPath.getParent(), target);

        final int NUM_ITERATIONS=10;
        StringBuilder   sb = new StringBuilder(d.length() * NUM_ITERATIONS * NUM_ITERATIONS);
        for (int j = 1; j <= NUM_ITERATIONS; j++) {
            if (sb.length() > 0) {
                sb.setLength(0);
            }

            for (int i = 0; i < j; i++) {
                sb.append(d);
            }

            sendFile(remotePath, sb.toString());
            assertFileLength(target, sb.length(), 5000);
            Files.delete(target);
        }
    }

    @Test
    public void testReadWriteWithOffset() throws Exception {
        Path targetPath = detectTargetFolder().toPath();
        Path lclSftp = Utils.resolve(targetPath, SftpConstants.SFTP_SUBSYSTEM_NAME, getClass().getSimpleName(), getCurrentTestName());
        Utils.deleteRecursive(lclSftp);
        Files.createDirectories(lclSftp);

        Path localPath = lclSftp.resolve(getCurrentTestName() + ".txt");
        String remotePath = Utils.resolveRelativeRemotePath(targetPath.getParent(), localPath);
        String data = getCurrentTestName();
        String extraData = "@" + getClass().getSimpleName();
        int appendOffset = -5;

        ChannelSftp c = (ChannelSftp) session.openChannel(SftpConstants.SFTP_SUBSYSTEM_NAME);
        c.connect();
        try {
            c.put(new ByteArrayInputStream(data.getBytes()), remotePath);
    
            assertTrue("Remote file not created after initial write: " + localPath, Files.exists(localPath));
            assertEquals("Mismatched data read from " + remotePath, data, readFile(remotePath));
    
            try(OutputStream os = c.put(remotePath, null, ChannelSftp.APPEND, appendOffset)) {
                os.write(extraData.getBytes());
            }
        } finally {
            c.disconnect();
        }

        assertTrue("Remote file not created after data update: " + localPath, Files.exists(localPath));
        
        String expected = data.substring(0, data.length() + appendOffset) + extraData;
        String actual = readFile(remotePath);
        assertEquals("Mismatched final file data in " + remotePath, expected, actual);
    }

    @Test
    public void testReadDir() throws Exception {
        ChannelSftp c = (ChannelSftp) session.openChannel(SftpConstants.SFTP_SUBSYSTEM_NAME);
        c.connect();
        try {
            URI url = getClass().getClassLoader().getResource(SshClient.class.getName().replace('.', '/') + ".class").toURI();
            URI base = new File(System.getProperty("user.dir")).getAbsoluteFile().toURI();
            String path = new File(base.relativize(url).getPath()).getParent() + "/";
            path = path.replace('\\', '/');
            Vector<?> res = c.ls(path);
            for (Object f : res) {
                System.out.println(f.toString());
            }
        } finally {
            c.disconnect();
        }
    }

    @Test
    public void testRealPath() throws Exception {
        ChannelSftp c = (ChannelSftp) session.openChannel(SftpConstants.SFTP_SUBSYSTEM_NAME);
        c.connect();

        try {
            URI url = getClass().getClassLoader().getResource(SshClient.class.getName().replace('.', '/') + ".class").toURI();
            URI base = new File(System.getProperty("user.dir")).getAbsoluteFile().toURI();
            String path = new File(base.relativize(url).getPath()).getParent() + "/";
            path = path.replace('\\', '/');
            String real = c.realpath(path);
            System.out.println(real);
            try {
                real = c.realpath(path + "/foobar");
                System.out.println(real);
                fail("Expected SftpException");
            } catch (com.jcraft.jsch.SftpException e) {
                // ok
            }
        } finally {
            c.disconnect();
        }
    }

    @Test
    public void testRename() throws Exception {
        Path targetPath = detectTargetFolder().toPath();
        Path lclSftp = Utils.resolve(targetPath, SftpConstants.SFTP_SUBSYSTEM_NAME, getClass().getSimpleName(), getCurrentTestName());
        Utils.deleteRecursive(lclSftp);
        Files.createDirectories(lclSftp);

        Path parentPath = targetPath.getParent();
        Path clientFolder = assertHierarchyTargetFolderExists(lclSftp.resolve("client"));

        try(SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            
            try (ClientSession session = client.connect(getCurrentTestName(), "localhost", port).verify(7L, TimeUnit.SECONDS).getSession()) {
                session.addPasswordIdentity(getCurrentTestName());
                session.auth().verify(5L, TimeUnit.SECONDS);
        
                try(SftpClient sftp = session.createSftpClient()) {
                    Path file1 = clientFolder.resolve(getCurrentTestName() + "-1.txt");
                    String file1Path = Utils.resolveRelativeRemotePath(parentPath, file1);
                    try (OutputStream os = sftp.write(file1Path, SftpClient.MIN_WRITE_BUFFER_SIZE)) {
                        os.write((getCurrentTestName() + "\n").getBytes());
                    }

                    Path file2 = clientFolder.resolve(getCurrentTestName() + "-2.txt");
                    String file2Path = Utils.resolveRelativeRemotePath(parentPath, file2);
                    Path file3 = clientFolder.resolve(getCurrentTestName() + "-3.txt");
                    String file3Path = Utils.resolveRelativeRemotePath(parentPath, file3);
                    try {
                        sftp.rename(file2Path, file3Path);
                        fail("Unxpected rename success of " + file2Path + " => " + file3Path);
                    } catch (org.apache.sshd.client.SftpException e) {
                        assertEquals("Mismatched status for failed rename of " + file2Path + " => " + file3Path, SSH_FX_NO_SUCH_FILE, e.getStatus());
                    }
            
                    try (OutputStream os = sftp.write(file2Path, SftpClient.MIN_WRITE_BUFFER_SIZE)) {
                        os.write("H".getBytes());
                    }
            
                    try {
                        sftp.rename(file1Path, file2Path);
                        fail("Unxpected rename success of " + file1Path + " => " + file2Path);
                    } catch (org.apache.sshd.client.SftpException e) {
                        assertEquals("Mismatched status for failed rename of " + file1Path + " => " + file2Path, SSH_FX_FILE_ALREADY_EXISTS, e.getStatus());
                    }

                    sftp.rename(file1Path, file2Path, SftpClient.CopyMode.Overwrite);
                }
            } finally {
                client.stop();
            }
        }
    }

    @Test
    public void testCopyFileExtension() throws Exception {
        Path targetPath = detectTargetFolder().toPath();
        Path lclSftp = Utils.resolve(targetPath, SftpConstants.SFTP_SUBSYSTEM_NAME, getClass().getSimpleName(), getCurrentTestName());
        Utils.deleteRecursive(lclSftp);
        Files.createDirectories(lclSftp);

        byte[] data = (getClass().getName() + "#" + getCurrentTestName()).getBytes(StandardCharsets.UTF_8);
        Path srcFile = lclSftp.resolve("src.txt");
        Files.write(srcFile, data, IoUtils.EMPTY_OPEN_OPTIONS);

        Path parentPath = targetPath.getParent();
        String srcPath = Utils.resolveRelativeRemotePath(parentPath, srcFile);
        Path dstFile = lclSftp.resolve("dst.txt");
        String dstPath = Utils.resolveRelativeRemotePath(parentPath, dstFile);
        
        LinkOption[] options = IoUtils.getLinkOptions(false);
        assertFalse("Destination file unexpectedly exists", Files.exists(dstFile, options));

        try(SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            
            try (ClientSession session = client.connect(getCurrentTestName(), "localhost", port).verify(7L, TimeUnit.SECONDS).getSession()) {
                session.addPasswordIdentity(getCurrentTestName());
                session.auth().verify(5L, TimeUnit.SECONDS);
                
                try(SftpClient sftp = session.createSftpClient()) {
                    CopyFileExtension ext = assertExtensionCreated(sftp, CopyFileExtension.class);
                    ext.copyFile(srcPath, dstPath, false);
                    assertTrue("Source file not preserved", Files.exists(srcFile, options));
                    assertTrue("Destination file not created", Files.exists(dstFile, options));
                    
                    byte[] actual = Files.readAllBytes(dstFile);
                    assertArrayEquals("Mismatched copied data", data, actual);
                    
                    try {
                        ext.copyFile(srcPath, dstPath, false);
                        fail("Unexpected success to overwrite existing destination: " + dstFile);
                    } catch(IOException e) {
                        assertTrue("Not an SftpException", e instanceof SftpException);
                    }
                }
            } finally {
                client.stop();
            }
        }
    }

    @Test
    public void testMD5HashExtensionOnSmallFile() throws Exception {
        testMD5HashExtension((getClass().getName() + "#" + getCurrentTestName()).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testMD5HashExtensionOnLargeFile() throws Exception {
        byte[] seed = (getClass().getName() + "#" + getCurrentTestName() + System.getProperty("line.separator")).getBytes(StandardCharsets.UTF_8);
        final int TEST_SIZE = Byte.SIZE * SftpConstants.MD5_QUICK_HASH_SIZE; 
        try(ByteArrayOutputStream baos=new ByteArrayOutputStream(TEST_SIZE + seed.length)) {
            while (baos.size() < TEST_SIZE) {
                baos.write(seed);
            }

            testMD5HashExtension(baos.toByteArray());
        }
    }

    private void testMD5HashExtension(byte[] data) throws Exception {
        Path targetPath = detectTargetFolder().toPath();
        Path lclSftp = Utils.resolve(targetPath, SftpConstants.SFTP_SUBSYSTEM_NAME, getClass().getSimpleName(), getCurrentTestName());
        Utils.deleteRecursive(lclSftp);
        Files.createDirectories(lclSftp);

        Digest digest = BuiltinDigests.md5.create();
        digest.init();
        digest.update(data);

        byte[] expectedHash = digest.digest();
        byte[] quickHash = expectedHash;
        if (data.length > SftpConstants.MD5_QUICK_HASH_SIZE) {
            byte[] quickData = new byte[SftpConstants.MD5_QUICK_HASH_SIZE];
            System.arraycopy(data, 0, quickData, 0, quickData.length);
            digest = BuiltinDigests.md5.create();
            digest.init();
            digest.update(quickData);
            quickHash = digest.digest();
        }

        Path srcFile = lclSftp.resolve("src.txt");
        Files.write(srcFile, data, IoUtils.EMPTY_OPEN_OPTIONS);

        Path parentPath = targetPath.getParent();
        String srcPath = Utils.resolveRelativeRemotePath(parentPath, srcFile);
        String srcFolder = Utils.resolveRelativeRemotePath(parentPath, srcFile.getParent());

        try(SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            
            try (ClientSession session = client.connect(getCurrentTestName(), "localhost", port).verify(7L, TimeUnit.SECONDS).getSession()) {
                session.addPasswordIdentity(getCurrentTestName());
                session.auth().verify(5L, TimeUnit.SECONDS);
                
                try(SftpClient sftp = session.createSftpClient()) {
                    MD5FileExtension file = assertExtensionCreated(sftp, MD5FileExtension.class);
                    try {
                        byte[] actual = file.getHash(srcFolder, 0L, 0L, quickHash);
                        fail("Unexpected file success on folder=" + srcFolder + ": " + BufferUtils.printHex(':', actual));
                    } catch(IOException e) {    // expected - not allowed to hash a folder
                        assertTrue("Not an SftpException", e instanceof SftpException);
                    }

                    MD5HandleExtension hndl = assertExtensionCreated(sftp, MD5HandleExtension.class);
                    try(CloseableHandle dirHandle = sftp.openDir(srcFolder)) {
                        try {
                            byte[] actual = hndl.getHash(dirHandle, 0L, 0L, quickHash);
                            fail("Unexpected handle success on folder=" + srcFolder + ": " + BufferUtils.printHex(':', actual));
                        } catch(IOException e) {    // expected - not allowed to hash a folder
                            assertTrue("Not an SftpException", e instanceof SftpException);
                        }
                    }

                    try(CloseableHandle fileHandle = sftp.open(srcPath, SftpClient.OpenMode.Read)) {
                        for (byte[] qh : new byte[][] { GenericUtils.EMPTY_BYTE_ARRAY, quickHash }) {
                            for (boolean useFile : new boolean[] { true, false }) {
                                byte[] actualHash = useFile ? file.getHash(srcPath, 0L, 0L, qh) : hndl.getHash(fileHandle, 0L, 0L, qh);
                                String type = useFile ? file.getClass().getSimpleName() : hndl.getClass().getSimpleName();
                                if (!Arrays.equals(expectedHash, actualHash)) {
                                    fail("Mismatched hash for quick=" + BufferUtils.printHex(':', qh) + " using " + type
                                       + ": expected=" + BufferUtils.printHex(':', expectedHash)
                                       + ", actual=" + BufferUtils.printHex(':', actualHash));
                                }
                            }
                        }
                    }
                }
            } finally {
                client.stop();
            }
        }
    }
    private static <E extends SftpClientExtension> E assertExtensionCreated(SftpClient sftp, Class<E> type) {
        E instance = sftp.getExtension(type);
        assertNotNull("Extension not created: " + type.getSimpleName(), instance);
        assertTrue("Extension not supported: " + instance.getName(), instance.isSupported());
        return instance;
    }

    @Test
    public void testServerExtensionsDeclarations() throws Exception {
        try(SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            
            try (ClientSession session = client.connect(getCurrentTestName(), "localhost", port).verify(7L, TimeUnit.SECONDS).getSession()) {
                session.addPasswordIdentity(getCurrentTestName());
                session.auth().verify(5L, TimeUnit.SECONDS);

                try(SftpClient sftp = session.createSftpClient()) {
                    Map<String,byte[]> extensions = sftp.getServerExtensions();
                    for (String name : new String[] {
                            SftpConstants.EXT_NEWLINE, SftpConstants.EXT_VERSIONS,
                            SftpConstants.EXT_VENDORID,
                            SftpConstants.EXT_SUPPORTED, SftpConstants.EXT_SUPPORTED2
                        }) {
                        assertTrue("Missing extension=" + name, extensions.containsKey(name));
                    }
                    
                    Map<String,?> data = ParserUtils.parse(extensions);
                    for (Map.Entry<String,?> de : data.entrySet()) {
                        String extName = de.getKey();
                        Object extValue = de.getValue();
                        System.out.append('\t').append(extName).append(": ").println(extValue);
                        if (SftpConstants.EXT_SUPPORTED.equalsIgnoreCase(extName)) {
                            assertSupportedExtensions(extName, ((Supported) extValue).extensionNames);
                        } else if (SftpConstants.EXT_SUPPORTED2.equalsIgnoreCase(extName)) {
                            assertSupportedExtensions(extName, ((Supported2) extValue).extensionNames);
                        }
                    }
                    
                    for (BuiltinSftpClientExtensions type : BuiltinSftpClientExtensions.VALUES) {
                        String extensionName = type.getName();
                        SftpClientExtension instance = sftp.getExtension(extensionName);
                        assertNotNull("Extension not implemented:" + extensionName, instance);
                        assertEquals("Mismatched instance name", extensionName, instance.getName());
                        assertTrue("Extension not supported: " + extensionName, instance.isSupported());
                    }
                }
            } finally {
                client.stop();
            }
        }
    }

    private static Set<String> EXPECTED_EXTENSIONS = SftpSubsystem.DEFAULT_SUPPORTED_CLIENT_EXTENSIONS; 
    private static void assertSupportedExtensions(String extName, Collection<String> extensionNames) {
        assertEquals(extName + "[count]", EXPECTED_EXTENSIONS.size(), GenericUtils.size(extensionNames));

        for (String name : EXPECTED_EXTENSIONS) {
            assertTrue(extName + " - missing " + name, extensionNames.contains(name));
        }
    }

    @Test
    public void testSftpVersionSelector() throws Exception {
        final AtomicInteger selected = new AtomicInteger(-1);
        SftpVersionSelector selector = new SftpVersionSelector() {
                @Override
                public int selectVersion(int current, List<Integer> available) {
                    int numAvailable = GenericUtils.size(available);
                    Integer maxValue = null;
                    if (numAvailable == 1) {
                        maxValue = available.get(0);
                    } else {
                        for (Integer v : available) {
                            if (v.intValue() == current) {
                                continue;
                            }
                            
                            if ((maxValue == null) || (maxValue.intValue() < v.intValue())) {
                                maxValue = v;
                            }
                        }
                    }

                    selected.set(maxValue.intValue());
                    return selected.get();
                }
            };

        try(SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            
            try (ClientSession session = client.connect(getCurrentTestName(), "localhost", port).verify(7L, TimeUnit.SECONDS).getSession()) {
                session.addPasswordIdentity(getCurrentTestName());
                session.auth().verify(5L, TimeUnit.SECONDS);

                try(SftpClient sftp = session.createSftpClient(selector)) {
                    assertEquals("Mismatched negotiated version", selected.get(), sftp.getVersion());
                    testClient(sftp);
                }
            } finally {
                client.stop();
            }
        }
    }

    private void testClient(SftpClient sftp) throws Exception {
        Path targetPath = detectTargetFolder().toPath();
        Path lclSftp = Utils.resolve(targetPath, SftpConstants.SFTP_SUBSYSTEM_NAME, getClass().getSimpleName(), getCurrentTestName());
        Utils.deleteRecursive(lclSftp);
        Files.createDirectories(lclSftp);

        Path parentPath = targetPath.getParent();
        Path clientFolder = lclSftp.resolve("client");
        String dir = Utils.resolveRelativeRemotePath(parentPath, clientFolder);
        String file = dir + "/" + getCurrentTestName() + ".txt";

        sftp.mkdir(dir);
        
        try(SftpClient.CloseableHandle h = sftp.open(file, EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create))) {
            byte[] d = "0123456789\n".getBytes();
            sftp.write(h, 0, d, 0, d.length);
            sftp.write(h, d.length, d, 0, d.length);
    
            SftpClient.Attributes attrs = sftp.stat(h);
            assertNotNull("No handle attributes", attrs);
        }            

        try(SftpClient.CloseableHandle h = sftp.openDir(dir)) {
            List<SftpClient.DirEntry> dirEntries = sftp.readDir(h);
            assertNotNull("No dir entries", dirEntries);
            
            boolean dotFiltered = false, dotdotFiltered = false;
            for (Iterator<SftpClient.DirEntry> it = dirEntries.iterator(); it.hasNext(); ) {
                SftpClient.DirEntry entry = it.next();
                String name = entry.filename;
                if (".".equals(name) && (!dotFiltered)) {
                    it.remove();
                    dotFiltered = true;
                } else if ("..".equals(name) && (!dotdotFiltered)) {
                    it.remove();
                    dotdotFiltered = true;
                }
            }

            assertEquals("Mismatched number of dir entries", 1, dirEntries.size());
            assertNull("Unexpected entry read", sftp.readDir(h));
        }

        sftp.remove(file);

        byte[] workBuf = new byte[IoUtils.DEFAULT_COPY_SIZE * Short.SIZE];
        new Random(System.currentTimeMillis()).nextBytes(workBuf);
        try (OutputStream os = sftp.write(file)) {
            os.write(workBuf);
        }

        try (InputStream is = sftp.read(file, IoUtils.DEFAULT_COPY_SIZE)) {
            int readLen = is.read(workBuf);
            assertEquals("Mismatched read data length", workBuf.length, readLen);

            int i = is.read();
            assertEquals("Unexpected read past EOF", -1, i);
        }

        SftpClient.Attributes attributes = sftp.stat(file);
        assertTrue("Test file not detected as regular", attributes.isRegularFile());

        attributes = sftp.stat(dir);
        assertTrue("Test directory not reported as such", attributes.isDirectory());

        int nb = 0;
        boolean dotFiltered = false, dotdotFiltered = false;
        for (SftpClient.DirEntry entry : sftp.readDir(dir)) {
            assertNotNull("Unexpected null entry", entry);
            String name = entry.filename;
            if (".".equals(name) && (!dotFiltered)) {
                dotFiltered = true;
            } else if ("..".equals(name) && (!dotdotFiltered)) {
                dotdotFiltered = true;
            } else {
                nb++;
            }
        }
        assertEquals("Mismatched read dir entries", 1, nb);

        sftp.remove(file);

        sftp.rmdir(dir);
    }

    @Test
    public void testCreateSymbolicLink() throws Exception {
        // Do not execute on windows as the file system does not support symlinks
        Assume.assumeTrue("Skip non-Unix O/S", OsUtils.isUNIX());

        Path targetPath = detectTargetFolder().toPath();
        Path lclSftp = Utils.resolve(targetPath, SftpConstants.SFTP_SUBSYSTEM_NAME, getClass().getSimpleName(), getCurrentTestName());
        Utils.deleteRecursive(lclSftp);
        Files.createDirectories(lclSftp);

        Path parentPath = targetPath.getParent();
        Path sourcePath = lclSftp.resolve(getCurrentTestName() + ".txt");
        String remSrcPath = Utils.resolveRelativeRemotePath(parentPath, sourcePath);
        Path linkPath = lclSftp.resolve("link-" + sourcePath.getFileName());
        String remLinkPath = Utils.resolveRelativeRemotePath(parentPath, linkPath);

        String data = getCurrentTestName();
        ChannelSftp c = (ChannelSftp) session.openChannel(SftpConstants.SFTP_SUBSYSTEM_NAME);
        c.connect();
        try {
            c.put(new ByteArrayInputStream(data.getBytes()), remSrcPath);
    
            assertTrue("Source file not created: " + sourcePath, Files.exists(sourcePath));
            assertEquals("Mismatched stored data in " + remSrcPath, data, readFile(remSrcPath));
    
            c.symlink(remSrcPath, remLinkPath);
    
            assertTrue("Symlink not created: " + linkPath, Files.exists(linkPath));
            assertEquals("Mismatche link data in " + remLinkPath, data, readFile(remLinkPath));
    
            String str1 = c.readlink(remLinkPath);
            String str2 = c.realpath(remSrcPath);
            assertEquals("Mismatched link vs. real path", str1, str2);
        } finally {
            c.disconnect();
        }
    }

    protected String readFile(String path) throws Exception {
        ChannelSftp c = (ChannelSftp) session.openChannel(SftpConstants.SFTP_SUBSYSTEM_NAME);
        c.connect();
        
        try(ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream is = c.get(path)) {

            byte[] buffer = new byte[256];
            int count;
            while (-1 != (count = is.read(buffer))) {
                bos.write(buffer, 0, count);
            }

            return bos.toString();
        } finally {
            c.disconnect();
        }
    }

    protected void sendFile(String path, String data) throws Exception {
        ChannelSftp c = (ChannelSftp) session.openChannel(SftpConstants.SFTP_SUBSYSTEM_NAME);
        c.connect();
        try {
            c.put(new ByteArrayInputStream(data.getBytes()), path);
        } finally {
            c.disconnect();
        }
    }

    private String randomString(int size) {
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            sb.append((char) ((i % 10) + '0'));
        }
        return sb.toString();
    }
}
