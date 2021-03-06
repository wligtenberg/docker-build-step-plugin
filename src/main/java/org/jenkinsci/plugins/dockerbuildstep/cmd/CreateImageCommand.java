package org.jenkinsci.plugins.dockerbuildstep.cmd;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Result;
import hudson.model.AbstractBuild;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.stream.JsonParsingException;

import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.dockerbuildstep.log.ConsoleLogger;
import org.jenkinsci.plugins.dockerbuildstep.util.Resolver;
import org.kohsuke.stapler.DataBoundConstructor;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.DockerException;

/**
 * This command creates a new image from specified Dockerfile.
 * 
 * @see http://docs.docker.com/reference/api/docker_remote_api_v1.13/#build-an-image-from-dockerfile-via-stdin
 * 
 * @author marcus
 * 
 */
public class CreateImageCommand extends DockerCommand {

	private final String dockerFolder;
	private final String imageTag;
	private final boolean noCache;

	@DataBoundConstructor
	public CreateImageCommand(String dockerFolder, String imageTag, boolean noCache) {
		this.dockerFolder = dockerFolder;
		this.imageTag = imageTag;
		this.noCache = noCache;
	}

	public String getDockerFolder() {
		return dockerFolder;
	}

	public String getImageTag() {
		return imageTag;
	}
	
	public boolean isNoCache() {
		return noCache;
	}

	@Override
	public void execute(@SuppressWarnings("rawtypes") AbstractBuild build,
			final ConsoleLogger console) throws DockerException {

		if (dockerFolder == null) {
			throw new IllegalArgumentException("dockerFolder is not configured");
		}

		if (imageTag == null) {
			throw new IllegalArgumentException("imageTag is not configured");
		}

		String dockerFolderRes = Resolver.buildVar(build, dockerFolder);
		String imageTagRes = Resolver.buildVar(build, imageTag);
		
		String expandedDockerFolder = expandEnvironmentVariables(dockerFolderRes,
				build, console);

		String expandedImageTag = expandEnvironmentVariables(imageTagRes, build,
				console);

		FilePath folder = new FilePath(new File(expandedDockerFolder));

		if (!exist(folder))
			throw new IllegalArgumentException("configured dockerFolder '"
					+ expandedDockerFolder + "' does no exist.");

		FilePath dockerFile = folder.child("Dockerfile");

		if (!exist(dockerFile))
			throw new IllegalArgumentException("configured dockerFolder '"
					+ folder + "' does not contain a Dockerfile.");

		DockerClient client = getClient();

		try {

			File docker = new File(expandedDockerFolder);

			console.logInfo("Creating docker image from " + docker.getAbsolutePath());

			InputStream istream = client.buildImageCmd(docker).withTag(expandedImageTag).withNoCache(noCache).exec();
			
			final List<JsonObject> errors = new ArrayList<JsonObject>();

			try {
				readJsonStream(istream, new JsonObjectCallback() {
					public void callback(JsonObject json) {
						if (json.containsKey("stream")) {
							console.log(json.getString("stream"));
						} else if (json.containsKey("status")) {
							console.log(json.getString("status"));
						} else {
							errors.add(json);
							console.logError(json.toString());
						}
					}
				});

				if (!errors.isEmpty()) {
					build.setResult(Result.FAILURE);
				} else {
					console.logInfo("Sucessfully created image " + expandedImageTag);
				}

			} finally {
				IOUtils.closeQuietly(istream);
			}

		} catch (Exception e) {
			throw new RuntimeException(e);
		}

	}

	private interface JsonObjectCallback {
		void callback(JsonObject jsonObject);
	}

	private void readJsonStream(InputStream istream, JsonObjectCallback callback)
			throws IOException, UnsupportedEncodingException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		byte[] buffer = new byte[8192];
		int count;
		while ((count = istream.read(buffer)) > 0) {
			baos.write(buffer, 0, count);
			String s = new String(baos.toByteArray(), "UTF-8");
			JsonReader reader = Json.createReader(new StringReader(s));
			JsonObject json = null;

			try {
				json = reader.readObject();
			} catch (JsonParsingException e) {
				// just ignore and continue
				continue;
			}

			baos.close();
			baos = new ByteArrayOutputStream();

			callback.callback(json);
		}
	}

	private String expandEnvironmentVariables(String string,
			@SuppressWarnings("rawtypes") AbstractBuild build, ConsoleLogger console) {
		try {
			return build.getEnvironment(console.getListener()).expand(string);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Extension
	public static class CreateImageCommandDescriptor extends
			DockerCommandDescriptor {
		@Override
		public String getDisplayName() {
			return "Create image";
		}
	}

	private boolean exist(FilePath filePath) throws DockerException {
		try {
			return filePath.exists();
		} catch (Exception e) {
			throw new DockerException("Could not check file", 0, e);
		}
	}

}
