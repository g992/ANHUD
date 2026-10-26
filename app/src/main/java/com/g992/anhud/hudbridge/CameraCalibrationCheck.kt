package com.g992.anhud.hudbridge

import com.g992.anhud.hudbridge.PerCarCalibration.Camera
import java.util.Base64

/**
 * Per-car camera calibration, found once per bridge session over the QNX shell that installs the
 * daemon: a sample of the AVM top-view mesh (/calibration/Table_2d.db, else the ground part of
 * Table.db) is fetched and every camera is fitted ([PerCarCalibration]). A camera whose fit is
 * missing or implausible gets NO_CORRECTION (plain picture). Results are cached per table file
 * (its `ls -ln` line), so the fit runs again only when the car's calibration changes.
 */
object CameraCalibrationCheck {
    enum class State {
        /** No bridge session has looked yet. */
        NOT_CHECKED,
        /** At least one camera has a plausible per-car fit. */
        AVAILABLE,
        /** No usable table, or no camera passed the checks. */
        MISSING,
        /** The shell call failed; treated like MISSING. */
        CHECK_FAILED
    }

    /** [fits] by CAMCFG role ([Gcp.CAM_ROLE_REAR] etc.); [notes]: why a camera has none. [key]: table `ls -ln` line. */
    class Result(
        val state: State,
        val fits: Map<Int, PerCarCalibration.Fit> = emptyMap(),
        val notes: Map<Int, String> = emptyMap(),
        val key: String? = null
    ) {
        fun fit(role: Int): PerCarCalibration.Fit? = fits[role]
    }

    val NOT_CHECKED = Result(State.NOT_CHECKED)

    /** Where the per-car result is kept between sessions. */
    interface Cache {
        fun load(key: String): Result?
        fun save(result: Result)
    }

    const val TABLE_2D = "/calibration/Table_2d.db"
    const val TABLE_3D = "/calibration/Table.db"
    /** Smaller files are not a real AVM table. */
    const val MIN_TABLE_BYTES = 1_000_000L
    const val LS_TIMEOUT_MS = 3_000L
    const val FETCH_TIMEOUT_MS = 15_000L
    /** Bump when the fit changes, so cached results are recomputed. */
    private const val CACHE_VERSION = 1

    /** Cameras the feature uses, by CAMCFG role. */
    val CAMERAS = mapOf(
        Gcp.CAM_ROLE_REAR to Camera.REAR,
        Gcp.CAM_ROLE_LEFT to Camera.LEFT,
        Gcp.CAM_ROLE_RIGHT to Camera.RIGHT
    )

    /** One table file from `ls -ln`; [line] identifies the car's calibration (size + mtime). */
    data class TableFile(val path: String, val size: Long, val line: String) {
        val groundOnly: Boolean
            get() = path != TABLE_2D
        val blockCount: Int
            get() = (size / PerCarCalibration.BLOCK_SIZE).toInt()
        val valid: Boolean
            get() = size >= MIN_TABLE_BYTES && size % PerCarCalibration.BLOCK_SIZE == 0L
        val cacheKey: String
            get() = "v$CACHE_VERSION|$line"
    }

    fun lsCommand(): String = "ls -ln $TABLE_2D $TABLE_3D 2>/dev/null"

    /** Lines like `-rw-r--r--  1 0  0  4065040 Oct 24 18:58 /calibration/Table_2d.db`. */
    fun parseLs(output: String): List<TableFile> = output.lines().mapNotNull { raw ->
        val line = raw.trim()
        val path = listOf(TABLE_2D, TABLE_3D).firstOrNull { line.endsWith(" $it") } ?: return@mapNotNull null
        val cols = line.split(Regex("\\s+"))
        if (cols.size < 6 || !cols[0].startsWith("-")) return@mapNotNull null
        val size = cols[4].toLongOrNull() ?: return@mapNotNull null
        TableFile(path, size, line)
    }

    /** Table_2d.db when usable, else Table.db (ground vertices only). Factory tables in /apps are no fallback. */
    fun pickTable(files: List<TableFile>): TableFile? =
        files.firstOrNull { it.path == TABLE_2D && it.valid } ?: files.firstOrNull { it.path == TABLE_3D && it.valid }

    /** 64 runs of 8 blocks spread over the file, base64 (~270 KB of text). Short line: offsets are computed by ksh. */
    fun fetchCommand(file: TableFile): String {
        val span = file.blockCount - PerCarCalibration.SAMPLE_CHUNK_BLOCKS
        val last = PerCarCalibration.SAMPLE_CHUNKS - 1
        return "i=0; while [ \$i -lt ${PerCarCalibration.SAMPLE_CHUNKS} ]; do " +
            "dd if=${file.path} bs=${PerCarCalibration.BLOCK_SIZE} " +
            "skip=\$(( (2*i*$span+$last)/${2 * last} )) count=${PerCarCalibration.SAMPLE_CHUNK_BLOCKS} 2>/dev/null; " +
            "i=\$((i+1)); done | openssl base64"
    }

    /** Base64 text to bytes; null unless it decodes to exactly [expected] bytes. */
    fun decodeBase64(output: String, expected: Int): ByteArray? {
        val text = output.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        val bytes = try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return bytes.takeIf { it.size == expected }
    }

    /** Fits every camera of a fetched sample. Never throws. */
    fun fromSample(sample: ByteArray, file: TableFile): Result {
        val points = try {
            PerCarCalibration.parseBlocks(sample, file.groundOnly)
        } catch (e: IllegalArgumentException) {
            return Result(State.MISSING, notes = CAMERAS.keys.associateWith { e.message ?: "bad table" }, key = file.cacheKey)
        }
        val results = PerCarCalibration.calibrateAll(points, CAMERAS.values.toList())
        val fits = HashMap<Int, PerCarCalibration.Fit>()
        val notes = HashMap<Int, String>()
        for ((role, camera) in CAMERAS) {
            val r = results.getValue(camera)
            if (r.fit != null) fits[role] = r.fit else notes[role] = r.reason
        }
        return Result(if (fits.isEmpty()) State.MISSING else State.AVAILABLE, fits, notes, file.cacheKey)
    }

    /**
     * The whole check over [shell]: ls, cached result or fetch + fit. Never throws (except
     * InterruptedException), never takes longer than the two shell timeouts.
     */
    fun check(shell: QnxShell, cache: Cache?): Result {
        return try {
            val file = pickTable(parseLs(shell.exec(lsCommand(), LS_TIMEOUT_MS).output))
                ?: return Result(State.MISSING, notes = CAMERAS.keys.associateWith { "нет таблицы калибровки" })
            cache?.load(file.cacheKey)?.let { return it }
            val text = shell.exec(fetchCommand(file), FETCH_TIMEOUT_MS)
            val sample = decodeBase64(text.output, PerCarCalibration.SAMPLE_BYTES)
                ?: return Result(State.CHECK_FAILED, notes = CAMERAS.keys.associateWith { "таблица не прочиталась" })
            fromSample(sample, file).also { cache?.save(it) }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Result(State.CHECK_FAILED, notes = CAMERAS.keys.associateWith { e.message ?: e.javaClass.simpleName })
        }
    }

    /** Correction for one view: the user wants it and that camera has a per-car fit. */
    fun correctionEnabled(pref: Boolean, fit: PerCarCalibration.Fit?): Boolean = pref && fit != null

    /** The settings toggle is blocked once a session found no calibration (or could not check). */
    fun correctionBlocked(state: State): Boolean = state == State.MISSING || state == State.CHECK_FAILED

    // ------------------------------------------------------------------ cache encoding

    /** Plain text: `state|key` line, then one `role:values` line per fit and `role#note` per failure. */
    fun encode(result: Result): String = buildString {
        append(result.state.name).append('|').append(result.key.orEmpty()).append('\n')
        for ((role, f) in result.fits) {
            val values = f.r.toList() + f.t.toList() + listOf(f.f, f.cx, f.cy, f.rms, f.maxErr, f.n.toDouble())
            append(role).append(':').append(values.joinToString(",")).append('\n')
        }
        for ((role, note) in result.notes) {
            append(role).append('#').append(note.replace('\n', ' ')).append('\n')
        }
    }

    fun decode(text: String?): Result? {
        if (text.isNullOrBlank()) return null
        return try {
            val lines = text.split('\n').filter { it.isNotEmpty() }
            val head = lines.first()
            val state = State.valueOf(head.substringBefore('|'))
            val key = head.substringAfter('|', "").ifEmpty { null }
            val fits = HashMap<Int, PerCarCalibration.Fit>()
            val notes = HashMap<Int, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                val hash = line.indexOf('#')
                if (hash > 0 && (colon < 0 || hash < colon)) {
                    notes[line.substring(0, hash).toInt()] = line.substring(hash + 1)
                    continue
                }
                val v = line.substring(colon + 1).split(',').map { it.toDouble() }
                require(v.size == 18)
                fits[line.substring(0, colon).toInt()] = PerCarCalibration.Fit(
                    r = v.subList(0, 9).toDoubleArray(),
                    t = v.subList(9, 12).toDoubleArray(),
                    f = v[12],
                    cx = v[13],
                    cy = v[14],
                    rms = v[15],
                    maxErr = v[16],
                    n = v[17].toInt()
                )
            }
            Result(state, fits, notes, key)
        } catch (e: Exception) {
            null
        }
    }
}
