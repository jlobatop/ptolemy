package alignment

/**
  * Author: Alex N. Salazar
  * Created on 15-8-2018
  * Contact a.n.salazar@tudelft.nl for questions
  *
  * Description:
  */

import java.io.{File, PrintWriter}

import atk.Tool.progress
import utilities.FileHandling._
import utilities.ReadUtils
import utilities.DatabaseUtils.GraphIndex
import utilities.MinimizerUtils.{MMethods, Minimizer}
import utilities.ReadAlignmentUtils
import utilities.GFAutils.ReadGFA
import utilities.NumericalUtils.{max, min}

import scala.collection.parallel.ForkJoinTaskSupport

object AlignReads extends ReadGFA with GraphIndex with ReadUtils with MMethods with ReadAlignmentUtils {

  case class Config(
                     canonicalQuiver: File = null,
                     reads: File = null,
                     db: File = null,
                     kmerSize: Int = 11,
                     windowSize: Int = 3,
                     proportion: Double = 0.6,
                     maxError: Double = 0.20,
                     chunkSize: Int = 30000,
                     minReadLength: Int = 500,
                     lengthProportion: Double = 0.3,
                     minHits: Int = 3,
                     maxThreads: Int = 1,
                     verbose: Boolean = false,
                     prefix: String = null,
                     outputDir: File = null,
                     debug: Boolean = false,
                   )

  def main(args: Array[String]) {
    val parser = new scopt.OptionParser[Config]("align-reads") {
      opt[File]('r', "reads") required() action { (x, c) =>
        c.copy(reads = x)
      } text ("Reads in FASTA, FASTQ, or gzipped-FASTQ format (detects automatically based on extension).")
      opt[File]('c', "canonical-quiver") required() action { (x, c) =>
        c.copy(canonicalQuiver = x)
      } text ("Output directory for database to be stored.")
      opt[File]("db") required() action { (x, c) =>
        c.copy(db = x)
      } text ("Directory path of database (e.g. output directory of 'extract' module).")
      opt[File]('o', "output-directory") required() action { (x, c) =>
        c.copy(outputDir = x)
      } text ("Output directory for database to be stored.")
      opt[String]('p', "prefix") required() action { (x, c) =>
        c.copy(prefix = x)
      } text ("Prefix for output file.")
      note("\nOPTIONAL\n")
      opt[Int]("minimum-length") action { (x, c) =>
        c.copy(kmerSize = x)
      } text ("Minimum read length (default is 500). Only read length of at least this size are processed.")
      opt[Int]('t', "threads") action { (x, c) =>
        c.copy(maxThreads = x)
      } text ("Maximum number of threads to use (default is 1).")
      opt[Int]('k', "kmer-size") action { (x, c) =>
        c.copy(kmerSize = x)
      } text ("Size of kmers (default is 11).")
      opt[Int]('w', "window-size") action { (x, c) =>
        c.copy(windowSize = x)
      } text ("Size of minimizer window (default is 3).")
      opt[Double]('e', "max-error") action { (x, c) =>
        c.copy(maxError = x)
      } text ("Maximum tolerable error for a read (default is 0.2). This influences the expected jaccard index " +
        "between a read and node.")
      opt[Double]('d', "distance-proportion") action { (x, c) =>
        c.copy(proportion = x)
      } text ("ORF proportion for maximum distance allowed between two minimizers during clustering. This is " +
        "based on the the size of an ORF. For example, given distance proportion, d, and size of an ORF, l, then the " +
        "max distance is: d*l.")
      opt[Double]('l', "length-ratio") action { (x, c) =>
        c.copy(lengthProportion = x)
      } text ("Maximum tolerable ratio between the the length of cluster and the size of a node (default is 0.3)")
      opt[Int]("min-hits") action { (x, c) =>
        c.copy(chunkSize = x)
      } text ("Minimum number of minimizer hits for candidate node alignments (default is 3).")
      opt[Int]("chunk-size") action { (x, c) =>
        c.copy(chunkSize = x)
      } text ("Number of reads to load at a time(default is 30000).")
      opt[Unit]("verbose") action { (x, c) =>
        c.copy(verbose = true)
      }
      opt[Unit]("debug") action { (x, c) =>
        c.copy(debug = true)
      }
    }
    parser.parse(args, Config()).map { config =>
      //check whether output directory exists. If not, create it.
      verifyDirectory(config.db)
      verifyFile(config.canonicalQuiver)
      alignReads(config)
    }
  }

  /**
    * The expected jaccard index based on the maximum tolerable error of a read and kmer size
    *
    * @return Double
    */
  def expectedJI: (Double, Int) => Double = (error, kmer_size) => 1 / (2 * Math.pow(Math.E, (error * kmer_size)) - 1)

  def alignReads(config: Config): Unit = {
    //set log level
    val verbose = config.verbose || config.debug
    //construct global kmer and node index
    val (global_node_kmer_index, global_inter_kmer_index, global_node_index, global_node_info_index) = {
      //get name of canonical quiver file
      val name = getFileName(config.canonicalQuiver)
      //get parent directory
      val parent_directory = getParentDirectory(config.canonicalQuiver)
      //fetch node kmer index file
      val node_kmer_index_file = parent_directory.listFiles().find(_.getName == name + ".nki")
      assert(node_kmer_index_file != None, "Could not find kmer index file for canonical quiver")
      //fetch intergenic kmer index file
      val inter_kmer_index_file = parent_directory.listFiles().find(_.getName == name + ".iki")
      assert(inter_kmer_index_file != None, "Could not find kmer index file for canonical quiver")
      //fetch node index file
      val node_index_file = parent_directory.listFiles().find(_.getName == name + ".ni")
      assert(node_index_file != None, "Could not find node index file for canonical quiver")
      //fetch node info index file
      val node_info_index_file = parent_directory.listFiles().find(_.getName == name + ".nii")
      assert(node_index_file != None, "Could not find node info index file for canonical quiver")
      println(timeStamp + "Loading global kmer index")
      //construct global node kmer index in the format of Map[kmer, Set[Node id]]:
      val gnki = loadGlobalNodeKmerIndex(node_kmer_index_file.get)
      println(timeStamp + "Loading intergenic index")
      //construct global intergenic kmer index in the format of Set[hashvalue]:
      val giki = loadGlobalInterKmerIndex(inter_kmer_index_file.get)
      println(timeStamp + "Loading global node index")
      //construct global node map in the format of Map[node id, MinKmer]:
      val gni = loadGlobalNodeIndex(node_index_file.get)
      println(timeStamp + "Loading global node info index")
      //construct global node info index in the format of Map[node id, (avg minimizer set size, avg seq size)]
      val gnii = loadGlobanNodeInfoIndex(node_info_index_file.get)
      //return indeces
      (gnki, giki, gni, gnii)
    }
    //average ratio of kmer set size to sequence length
    val average_kmer_set_size_ratio = {
      global_node_info_index.values.map(x => x._1.toDouble / x._2).sum / global_node_info_index.size
    }
    //get smallest gene
    val smallest_gene = global_node_info_index.values.minBy(_._2)._2

    println(timeStamp + "Average kmer set size ratio: " + average_kmer_set_size_ratio)
    println(timeStamp + "Smallest annotated gene: " + smallest_gene)

    //compute expected jaccard index for the given max tolerable error rate and kmer size
    val expected_ji = expectedJI(config.maxError, config.kmerSize)
    //compute expected minimizer hits
    def expectedMinHits: Int => Int = node => ((expected_ji) * global_node_info_index(node)._1).toInt

    println(timeStamp + "Expected Jaccard Index using maximum tolerable error rate of " + (config.maxError * 100) +
      "% " + "is " + (expected_ji * 100) + "%")

    //setting read minimizer method
    def extractReadMinimizers = getReadMinimizers(config.kmerSize, config.windowSize) _

    //create temporary file path
    val output_name = config.prefix + ".gfa"
    //create output file
    val pw = new PrintWriter(config.outputDir + "/." + output_name + ".tmp")
    //get read file type
    val read_file_type = determineFileType(config.reads)
    //load reads as iterator
    val read_iterator = {
      //load iterator depending on file type
      read_file_type match {
        case `fasta_extension` | `fastq_extension` => openFileWithIterator(config.reads)
        case `fastq_gz_extension` => openGZFileWithIterator(config.reads)
      }
    }
    var next_line: Option[String] = None
    println(timeStamp + "Loading from " + read_file_type + "-formatted file")
    //iterate through each read and align
    while (read_iterator.hasNext) {
      //load next chunk of reads depending on file type
      val (current, runoff_line) = {
        //check file type
        read_file_type match {
          //for a FASTA-formatted file
          case `fasta_extension` =>
            loadFastaChunk(read_iterator, List(), config.chunkSize, config.minReadLength, next_line, new StringBuilder)
          //for a FASTQ-formatted file
          case `fastq_extension` | `fastq_gz_extension` =>
            loadFastqChunk(read_iterator, List(), config.chunkSize, config.minReadLength, List())
        }
      }
      println(timeStamp + "Loaded " + current.size + " reads")
      //set maximum number of threads to use
      current.tasksupport = new ForkJoinTaskSupport(new scala.concurrent.forkjoin.ForkJoinPool(config.maxThreads))
      //get carry over read entry
      next_line = runoff_line
      println(timeStamp + "Starting alignments")
      //iterate through each read in parallel and align to graph
      current.foreach { current_read => {
        progress(config.chunkSize / 10)
        //get current read name, seq size, and minimizers
        val all_read_minimizers = extractReadMinimizers(current_read)
        //set method for predicted the coordinates of a node with configuration parameters
        val predict_coordinates = predictCoords(current_read.name, config.debug) _
        if (verbose) println(timeStamp + "Processing read " + current_read.name + " of " + current_read.length + " nt")
        //get overlapping nodes and minimizers hits as well as minimizer orphans
        val (node2kmers, minimizer_orphans) = {
          val tmp =
            all_read_minimizers.foldLeft((Map[Int, Set[Int]](), Set[(Int, Int)]())) {
              case ((_node2kmers, orphans), minimizer) => {
                //fetch nodes that contain the current kmer
                val fetch = global_node_kmer_index.get(minimizer._1)
                //no nodes contain current kmer
                if (fetch == None) {
                  (_node2kmers, minimizer._2.foldLeft(orphans)((b, a) =>
                    b + ((a.hashvalue, forwardPosition(a, current_read.length, config.kmerSize)))))
                }
                //add nodes to sequence of nodes
                else {
                  (fetch.get.foldLeft(_node2kmers)((local_node2kmers, node_id) => {
                    val current = local_node2kmers.getOrElse(node_id, Set[Int]())
                    local_node2kmers + (node_id -> (current + (minimizer._1)))
                  }), orphans)
                }
              }
            }
          //retain nodes with enough minimizer hits. this is based on the expected number of minimizers using the
          // average kmer set size.
          (tmp._1.filter(x =>{
            //to accomodate for differences in sequence sizes and the error bound, reduce threshold by 2 to the
            // minimizer hits to slightly decrease number. (hardcoded, not been optimized)
            x._2.size >= expectedMinHits(x._1) - 2
          }) , tmp._2)
        }
        //get aligned nodes in order in which it appears in the read in 4-tuple (node, (start,end), orientation, hits)
        // note: orientation is simply majority count of the read-node minimizer orientation occurrance
        val (aligned_nodes, failed_clusters) = {
          val parent_tmp = node2kmers.foldLeft((Seq[(Int, (Int, Int), Int, Int)](), Set[Int]())) {
            case ((found_nodes, _failed_clusters), (node, kmers)) => {
              if (verbose) println(timeStamp + "--Processing node " + node + " with " + kmers.size + " kmers")
              //get average minimizer set size and seq size for current node
              val (avg_ms, avg_seq_size) = global_node_info_index(node)
              //compute maximum distance threshold
              val max_dist = (avg_seq_size * config.proportion).toInt
              //get the most dense cluster that are epsilon-based chained
              val (max_dense_cluster, total_clusters) = {
                //cluster minimizers on the read
                val tmp = clusterMinimizers(kmers.toSeq.map(all_read_minimizers(_)).flatten.toList,
                  config.kmerSize, current_read.length, max_dist, config.debug)
                if (verbose) println(timeStamp + "----Found " + tmp.size + " clusters")
                if (config.debug)
                  println(timeStamp + "----Formed the following clusters: " + tmp.map(_.map(_._2).toList.sorted))
                (if (tmp.isEmpty) Set[(Minimizer, Int)]() else tmp.maxBy(_.size), tmp.size)
              }
              //get cluster width
              val cluster_width = (max_dense_cluster.maxBy(_._2)._2 - max_dense_cluster.minBy(_._2)._2) + 1
              //ratio between the size of the cluster and the node
              val size_difference_ratio = min(cluster_width, avg_seq_size).toDouble / max(cluster_width, avg_seq_size)
              if (verbose) {
                println(timeStamp + "----Using max dense cluster of " + max_dense_cluster.size + " minimizers")
                println(timeStamp + "----Cluster length of: " + cluster_width)
                println(timeStamp + "----Size difference ratio: " + size_difference_ratio)
              }
              //get total unique minimizer hits
              val unique_hits = max_dense_cluster.map(_._1.hashvalue).size
              //get expected number of minimizer hits
              val expected_hits = expectedMinHits(node)
              if (verbose) println(timeStamp + "----Expected " + expected_hits + "minimizer hits based on mean " +
                  "minimizer set size of " + avg_ms)
              //size ratio is too low
              if (size_difference_ratio < config.lengthProportion) (found_nodes, _failed_clusters)
              //jaccard index of node is below expected but has enough minimizer hits for something potentially relevant
              else if (unique_hits < expected_hits && unique_hits >= config.minHits)
                (found_nodes, max_dense_cluster.foldLeft(_failed_clusters)((b, a) => b + (a._2)))
              //jaccard index is at least of the expected score, consider alignment
              else {
                //create a map of the minimizer hashvalue -> orientation(s)
                val read_kmers2orientation = max_dense_cluster.foldLeft(Map[Int, Seq[Int]]()) {
                  case (ro_map, (minimizer, position)) => {
                    val current = ro_map.getOrElse(minimizer.hashvalue, Seq[Int]())
                    ro_map + (minimizer.hashvalue -> (current.:+(minimizer.orientation)))
                  }
                }
                //pair the orientations of a read minimizer hit to the node minimizer hit and count their occurrances
                // as well as creating a 2-tuple of (read-forward position, node-forward position)
                val (orientation, start, end) = {
                  //fetch all node minimizers and iterate through them with map of orientation occurance -> count
                  val tmp = global_node_index(node).foldLeft((Map[Int, Int](), Seq[(Int, Int)]())) {
                    case ((zo_map, read2node_positions), minimizer) => {
                      //attempt to get the corresponding orientation of current minimizer in the read
                      val read_orientations = read_kmers2orientation.get(minimizer.hashvalue)
                      //minimizer does not exist in read, move on
                      if (read_orientations.isEmpty) (zo_map, read2node_positions)
                      else {
                        val node_forward_position = forwardPosition(minimizer, avg_seq_size, config.kmerSize)
                        //iterate through each orientation
                        (read_orientations.get.foldLeft(zo_map)((local_zo_map, read_orientation) => {
                          //determine orientation of minimizer instance
                          val orientation_instance = getOrientation(read_orientation, minimizer.orientation)
                          //get the current count of the orientation occurrance
                          val current = local_zo_map.getOrElse(orientation_instance, 0)
                          //increment accordingly
                          local_zo_map + (orientation_instance -> (current + 1))
                        }),
                          //get corresponding read minimizer(s) and add forward positions to sequence
                          all_read_minimizers(minimizer.hashvalue)
                            .foldLeft(read2node_positions)((local_read2node_pos, rm) => {
                            local_read2node_pos.:+(
                              (forwardPosition(rm, current_read.length, config.kmerSize), node_forward_position))
                          }))
                      }
                    }
                  }
                  //get orientation of alignment, this is just to be able to output occurrance map in debug mode
                  val final_orientation = tmp._1.toList.sortBy(-_._2).head._1
                  if (config.debug) println(timeStamp + "----Orientation map: " + tmp._1)
                  //predict best start/end coordinates
                  val (_start, _end) = predict_coordinates(node, final_orientation, avg_seq_size, tmp._2.sortBy(_._2))
                  //return oreitnation and coords
                  (final_orientation, _start, _end)
                }
                //move on if could not find reliable coordinates
                if (start == -1 && end == -1) {
                  if (config.debug) {
                    println("----WARNING: Could not find reliable coordinates for read " + current_read.name +
                      " during alignment with node " + node + " using max dense cluster of " + max_dense_cluster +
                      ". Skipping node alignment")
                  }
                  (found_nodes, max_dense_cluster.foldLeft(_failed_clusters)((b, a) => b + (a._2)))
                }
                else {
                  if (verbose) {
                    println(timeStamp + "----Node is alignable.")
                    println(timeStamp + "----Node has an average size of: " + avg_seq_size)
                    println(timeStamp + "----Predicted start/end coordinates: " + (start, end))
                    println(timeStamp + "----Choosing orientation: " + orientation)
                  }
                  (found_nodes.:+((node, (start, end), orientation, unique_hits)), _failed_clusters)
                }
              }
            }
          }
          (parent_tmp._1.sortBy(x => (x._2._1, x._2._2)), parent_tmp._2)
        }
        //if read is unmapped
        if (aligned_nodes.isEmpty) {
          if (verbose) println(timeStamp + "--No alignments found")
          pw.println("P" + "\t" + current_read.name + "\t\t" + current_read.length + "M")
        }
        //read is mapped
        else {
          if (config.debug) println(timeStamp + "Found the following alignment: " + aligned_nodes)
          //curate alignments by identifying ambiguous alignments and processing unaligned regions
          val curated_alignments = {
            //get initial curated alignments containing ambiugous alignemnts
            val tmp = curateAlignments(aligned_nodes, current_read.length,
              (smallest_gene * 0.8).toInt, average_kmer_set_size_ratio)
            if (config.debug) println(timeStamp + "Initial curated alignment: " + tmp)
            //iterate through each alignment and process unaligned regions
            tmp.filter(alignment =>
              //alignment is not an unaligned region, retain
              alignment._1 != -2 || {
                //count occurrances of orphan minimizers in current unaligned region that are from failed clusters or
                // intergenic regions
                val (from_failed, from_inter) = minimizer_orphans.foldLeft((0, 0)) {
                  case ((_from_failed, _from_inter), orphan) => {
                    //minimizer does not overlap, move on
                    if (!(alignment._2._1 <= orphan._2 && alignment._2._2 >= orphan._2)) (_from_failed, _from_inter)
                    //minimizer has been seen in a failed cluster, increment. note: priority here
                    else if (failed_clusters(orphan._1)) (_from_failed + 1, _from_inter)
                    //minimizer has been seen in an intergenic region
                    else if (global_inter_kmer_index(orphan._1)) (_from_failed, _from_inter)
                    //minimizer ahs never been seen
                    else (_from_failed, _from_inter)
                  }
                }
                //retain as ambiguous alignment if:
                //  number of hits to failed clusters exceeds minimum
                from_failed >= config.minHits - 1 ||
                  //  or its too disimilar to an intergenic region of the expected size size
                  (from_inter / (0.5 * (alignment._2._2 - alignment._2._1 + 1)) < (expected_ji))
              })
          }
          //get maximum contiguous alignments (i.e. split alignments), and each one with their orientation
          val max_contiguous_alignments = {
            // get max contiguous alignments
            val tmp = maxContiguousAlignments(curated_alignments)
            //determine cumulative orientation of alignments
            val orientation = determineAlignmentOrientation(tmp.flatten)
            //reverser if needed
            if (orientation == '-') tmp.reverse.map(_.reverse)
            else tmp
          }
          if (config.debug) {
            println(timeStamp + "Curated alignment: " + curated_alignments)
            println(timeStamp + "Split alignments: " + max_contiguous_alignments)
            //unaligned.foreach(region => println(region, failed_clusters.filter(x => region._1 <= x && region._2 >=x).size))
          }
          //create alignment string for each max contiguous alignment
          val aligned_structure = max_contiguous_alignments.map(_.map(_._1 + "+").mkString(","))
          if (verbose) println(timeStamp + "--Found the following alignment(s): " + aligned_structure.mkString(";"))
          //read is unmapped
          if (max_contiguous_alignments.forall(_.isEmpty))
            pw.println("P" + "\t" + current_read.name + "\t\t" + current_read.length + "M")
          //read is mapped somewhere
          else aligned_structure.foreach(alignment =>
            pw.println("P" + "\t" + current_read.name + "\t" + alignment + "\t" + current_read.length + "M"))
        }
      }
      }
    }

    pw.close
    println(timeStamp + "Alignment completed")
    updateCanonicalQuiver(config.outputDir, output_name, config.canonicalQuiver)
  }

  /**
    * Method to update canonical quiver
    */
  def updateCanonicalQuiver(output_directory: File, output_name: String, cq: File): Unit = {
    val tmp_output = new File(output_directory + "/." + output_name + ".tmp")
    println(timeStamp + "Computing edge coverage")
    //iterate through alignments and keep track of edges and their coverage as well as total reads and unmapped
    val (edge_coverage, node_coverage, total_alignments, unmapped) = getAlignmentStats(tmp_output)
    println(timeStamp + "Found " + total_alignments + " total alignments, of which " +
      (unmapped.toDouble / total_alignments) * 100 + "% are unmapped")
    println(timeStamp + "Writing coverage to disk")
    val pw = new PrintWriter(output_directory + "/" + output_name)
    //load canonical quiver and add coverage information while retaining new edges
    openFileWithIterator(cq).foldLeft(edge_coverage)((filtered_coverage, line) => {
      //get line type
      val line_type = line.split("\t").head
      line_type match {
        //edge line
        case "L" => {
          //get edge
          val edge = parseLinkLine(line)
          //get coverage
          val coverage = edge_coverage.getOrElse(edge, 0)
          pw.println(line + "\tRC:" + coverage)
          if (coverage == 0) filtered_coverage else filtered_coverage - edge
        }
        //node line
        case "S" => {
          //get node
          val node = parseSegmentLine(line)
          //get coverage
          val coverage = node_coverage.getOrElse(node, 0)
          pw.println(line + "\tRC:" + coverage)
          filtered_coverage
        }
        //Something else, move on
        case _ => {
          pw.println(line)
          filtered_coverage
        }
      }
    })
      //add new edges to output
      .foldLeft(0)((index, new_edge) => {
      if (index == 0) pw.println("#NEW EDGES FROM READ ALIGNMENTS")
      pw.println("L\t" + new_edge._1._1 + "\t+\t" + new_edge._1._2 + "\t+\t1M\tRC:" + new_edge._2)
      index + 1
    })
    //iterate through alignments once more and output to file
    pw.println("#READ ALIGNMENTS")
    openFileWithIterator(tmp_output).foreach(pw.println)
    pw.close()
    tmp_output.delete()
    println("Successfully completed!")
  }
}
