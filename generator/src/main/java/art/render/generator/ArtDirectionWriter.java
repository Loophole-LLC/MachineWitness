package art.render.generator;

import java.io.IOException;

/** One model's take: researches this week's headlines and writes its own art direction. */
public interface ArtDirectionWriter {

    ArtDirection write(WeeklyDigest digest) throws IOException, InterruptedException;
}
