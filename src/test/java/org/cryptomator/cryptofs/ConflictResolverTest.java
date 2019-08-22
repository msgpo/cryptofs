package org.cryptomator.cryptofs;

import com.google.common.base.Strings;
import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.FileNameCryptor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConflictResolverTest {

	private LongFileNameProvider longFileNameProvider;
	private Cryptor cryptor;
	private FileNameCryptor filenameCryptor;
	private ConflictResolver conflictResolver;
	private String dirId;
	private Path tmpDir;

	@BeforeEach
	public void setup(@TempDir Path tmpDir) {
		this.tmpDir = tmpDir;
		this.longFileNameProvider = Mockito.mock(LongFileNameProvider.class);
		this.cryptor = Mockito.mock(Cryptor.class);
		this.filenameCryptor = Mockito.mock(FileNameCryptor.class);
		this.conflictResolver = new ConflictResolver(longFileNameProvider, cryptor);
		this.dirId = "foo";

		Mockito.when(cryptor.fileNameCryptor()).thenReturn(filenameCryptor);
	}

	private ArgumentMatcher<Path> hasFileName(String name) {
		return path -> {
			if (path == null) {
				return false;
			}
			Path filename = path.getFileName();
			assert filename != null;
			return filename.toString().equals(name);
		};
	}

	private Answer<Integer> fillBufferWithBytes(byte[] bytes) {
		return invocation -> {
			ByteBuffer buffer = invocation.getArgument(0);
			buffer.put(bytes);
			return bytes.length;
		};
	}

	@ParameterizedTest
	@ValueSource(strings = {
			".DS_Store",
			"FooBar==.c9r",
			"FooBar==.c9s",
	})
	public void testPassthroughNonConflictingFiles(String conflictingFileName) throws IOException {
		Path conflictingPath = tmpDir.resolve(conflictingFileName);

		Path result = conflictResolver.resolveConflictsIfNecessary(conflictingPath, dirId);

		Assertions.assertSame(conflictingPath, result);
		Mockito.verifyNoMoreInteractions(filenameCryptor);
		Mockito.verifyNoMoreInteractions(longFileNameProvider);
	}

	@ParameterizedTest
	@CsvSource({
			"FooBar== (2).c9r,FooBar==.c9r",
			"FooBar== (2).c9s,FooBar==.c9s",
	})
	public void testResolveTrivially(String conflictingFileName, String expectedCanonicalName) throws IOException {
		Path conflictingPath = tmpDir.resolve(conflictingFileName);
		Files.createFile(conflictingPath);

		Path result = conflictResolver.resolveConflictsIfNecessary(conflictingPath, dirId);

		Assertions.assertEquals(tmpDir.resolve(expectedCanonicalName), result);
		Mockito.verifyNoMoreInteractions(filenameCryptor);
		Mockito.verifyNoMoreInteractions(longFileNameProvider);
	}

	@ParameterizedTest
	@CsvSource({
			"FooBar== (2).c9r,FooBar==.c9r,dir.c9r",
			"FooBar== (2).c9s,FooBar==.c9s,symlink.c9r",
	})
	public void testResolveTriviallyForIdenticalContent(String conflictingFileName, String expectedCanonicalName, String contentFile) throws IOException {
		Path conflictingPath = tmpDir.resolve(conflictingFileName);
		Path canonicalPath = tmpDir.resolve(expectedCanonicalName);
		Files.createDirectory(conflictingPath);
		Files.createDirectory(canonicalPath);
		Files.write(conflictingPath.resolve(contentFile), new byte[5]);
		Files.write(canonicalPath.resolve(contentFile), new byte[5]);

		Path result = conflictResolver.resolveConflictsIfNecessary(conflictingPath, dirId);

		Assertions.assertEquals(canonicalPath, result);
		Mockito.verifyNoMoreInteractions(filenameCryptor);
		Mockito.verifyNoMoreInteractions(longFileNameProvider);
	}

	@Test
	public void testResolveByRenamingRegularFile() throws IOException {
		String conflictingName = "FooBar== (2).c9r";
		String canonicalName = "FooBar==.c9r";
		Path conflictingPath = tmpDir.resolve(conflictingName);
		Path canonicalPath = tmpDir.resolve(canonicalName);
		Files.write(conflictingPath, new byte[3]);
		Files.write(canonicalPath, new byte[5]);

		Mockito.when(longFileNameProvider.isDeflated(Mockito.eq(canonicalName))).thenReturn(false);
		Mockito.when(filenameCryptor.decryptFilename(Mockito.any(), Mockito.eq("FooBar=="), Mockito.any())).thenReturn("cleartext.txt");
		Mockito.when(filenameCryptor.encryptFilename(Mockito.any(), Mockito.eq("cleartext.txt (Conflict 1)"), Mockito.any())).thenReturn("BarFoo==");

		Path result = conflictResolver.resolveConflictsIfNecessary(conflictingPath, dirId);

		Assertions.assertEquals("BarFoo==.c9r", result.getFileName().toString());
		Assertions.assertFalse(Files.exists(conflictingPath));
		Assertions.assertTrue(Files.exists(result));
	}

	@Test
	public void testResolveByRenamingShortenedFile() throws IOException {
		String conflictingName = "FooBar== (2).c9s";
		String canonicalName = "FooBar==.c9s";
		String inflatedName = Strings.repeat("a", Constants.SHORT_NAMES_MAX_LENGTH + 1);
		Path conflictingPath = tmpDir.resolve(conflictingName);
		Path canonicalPath = tmpDir.resolve(canonicalName);
		Files.write(conflictingPath, new byte[3]);
		Files.write(canonicalPath, new byte[5]);

		Mockito.when(longFileNameProvider.isDeflated(canonicalName)).thenReturn(true);
		Mockito.when(longFileNameProvider.inflate(canonicalPath)).thenReturn(inflatedName);
		Mockito.when(filenameCryptor.decryptFilename(Mockito.any(), Mockito.eq(inflatedName), Mockito.any())).thenReturn("cleartext.txt");
		String resolvedCiphertext = Strings.repeat("b", Constants.SHORT_NAMES_MAX_LENGTH + 1);
		Path resolvedInflatedPath = canonicalPath.resolveSibling(resolvedCiphertext + Constants.CRYPTOMATOR_FILE_SUFFIX);
		Path resolvedDeflatedPath = canonicalPath.resolveSibling("BarFoo==.c9s");
		Mockito.when(filenameCryptor.encryptFilename(Mockito.any(), Mockito.eq("cleartext.txt (Conflict 1)"), Mockito.any())).thenReturn(resolvedCiphertext);
		Mockito.when(longFileNameProvider.deflate(resolvedInflatedPath)).thenReturn(resolvedDeflatedPath);

		Path result = conflictResolver.resolveConflictsIfNecessary(conflictingPath, dirId);

		Assertions.assertEquals(resolvedDeflatedPath, result);
		Assertions.assertFalse(Files.exists(conflictingPath));
		Assertions.assertTrue(Files.exists(result));
	}

}
