package test

import com.amazonaws.regions.Regions
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.S3Event
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.GetObjectRequest
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.io.*
import java.net.URLDecoder
import java.sql.Timestamp
import java.util.stream.Collectors

class CopyTrimLab : RequestHandler<S3Event, String> {

    fun mapToItem (line: String): GenDataLab {
        val snpsmap:MutableMap<String, String> = mutableMapOf()
        val words = line.split(regex = "[,]".toRegex())
                .dropLastWhile({ it.isEmpty() }).toTypedArray()
        var id = ""
        for (i in words.indices) {
            if(i == 0) {
                id = words[0]
            } else {
                snpsmap.put(snplist[i], words[i].replace(":", ""))
            }
        }
        return GenDataLab(id, snpsmap)
    }

    var snplist:List<String> = emptyList()

    fun mapsnplist (line: String): MutableList<String> {
        val snplist:MutableList<String> = mutableListOf<String>()
        val snps = line.split(regex = "[,]".toRegex())
                .dropLastWhile({ it.isEmpty() }).toTypedArray()
        for(snp in snps) {
            snplist.add(snp)
        }
        println("SNP list : ${snplist}, size = ${snplist.size}")
        return snplist
    }

    override fun handleRequest(s3event: S3Event, context: Context): String {
        try {
            val record = s3event.records[0]

            val srcBucket = record.s3.bucket.name
            val userid = record.userIdentity.principalId
            val datetime = record.eventTime
            // Object key may have spaces or unicode non-ASCII characters.
            var srcKey = record.s3.`object`.key
                    .replace('+', ' ')
            srcKey = URLDecoder.decode(srcKey, "UTF-8")
            val date = Timestamp(datetime.millis)
            val dstBucket = "report-lab-trimmed"

            // Download the text from S3 into a stream
            val s3Client = AmazonS3ClientBuilder.standard()
                    .withRegion(Regions.EU_WEST_2)
                    //.disableChunkedEncoding()
                    .build()
            println("Reading from: $srcBucket/$srcKey")
            val s3Object = s3Client.getObject(GetObjectRequest(srcBucket, srcKey))
            val objectData = s3Object.objectContent
            val bufferedReader = BufferedReader(InputStreamReader(objectData))

            // skip the header of the csv
            snplist = (bufferedReader.lines().skip(7).limit(1).map(::mapsnplist)
                    .collect(Collectors.toList()))!![0]

            val genDataLabList: List<GenDataLab> = bufferedReader.lines().skip(8).map(::mapToItem).collect(Collectors.toList())
            println("GenDataLab list : ${genDataLabList}")
            bufferedReader.close()
/*
            val snplist = arrayOf("rs1801133", "rs4343", "rs6731545", "rs5082", "rs7412",
                    "rs429358", "rs5128", "rs2472300", "rs1801282", "rs174547", "rs9939609", "rs4988235",
                    "rs1761667", "rs17782313", "rs4143094", "rs7579", "rs1695", "rs1050450", "rs7903146",
                    "rs10741657", "rs2282679", "rs1799983", "rs4880", "rs1800566", "rs713598", "rs1726866",
                    "rs10246939", "rs239345", "rs3785368", "rs8065080", "rs307355", "rs35744813", "rs671",
                    "rs1815739", "rs4343", "rs1042713", "rs2395182", "rs7775228", "rs2187668", "rs4639334",
                    "rs7454108", "rs4713586")
*/
            for (genDataLab in genDataLabList) {

                val bufferedWriter = BufferedWriter(
                        FileWriter("/tmp/${genDataLab.id}.csv"))

                val csvPrinter = CSVPrinter(bufferedWriter, CSVFormat.DEFAULT
                        .withHeader("rsid", "genotype"))

                for(snp in genDataLab.snps) {
                    csvPrinter.printRecord(snp.key, snp.value)
                }
                bufferedWriter.close()
                val dstKey = "labid=${genDataLab.id}/date=$date/trimmed-$srcKey"
                // Uploading to S3 destination bucket
                println("Writing to: $dstBucket/$dstKey")
                s3Client.putObject(dstBucket, dstKey, File("/tmp/${genDataLab.id}.csv"))
                println("Successfully trimmed " + srcBucket + "/" + srcKey + " and partially uploaded to "
                        + dstBucket + "/" + dstKey)
            }
            return "OK"
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

    }
}
