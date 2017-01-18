package spotlight.analysis.algorithm.skyline.adf

import org.apache.commons.math3.linear.MatrixUtils
import org.scalatest._
import peds.commons.log.Trace


/**
  *
  * Created by rolfsd on 3/6/16.
  */
class RPCASpec
extends fixture.WordSpec
with MustMatchers
with ParallelTestExecution {
  val trace = Trace[RPCASpec]

  override type FixtureParam = Fixture

  class Fixture { outer =>
    def vectorToMatrix( x: Array[Double], rows: Int, cols: Int ): Array[Array[Double]] = {
      val input2D = MatrixUtils.createRealMatrix( rows, cols )
      for {
        n <- 0 until x.size
        i = n % rows
        j = math.floor( n / rows ).toInt
      } { input2D.addToEntry( i, j, x(n) ) }
      input2D.getData
    }

    def matrixApproximatelyEquals( X: Array[Array[Double]], Y: Array[Array[Double]], epsilon: Double ): Boolean = {
      val xs = X.flatten
      val ys = Y.flatten
      xs.zip( ys ).exists{ xy => math.abs( xy._1 - xy._2 ) > epsilon }
    }
  }

  def createFixture(): Fixture = new Fixture

  override def withFixture( test: OneArgTest ): Outcome = {
    val fixture = createFixture()
    try {
      test( fixture )
    } finally {
    }
  }

  object WIP extends Tag( "wip" )


  "FPCA" should {
    "work :-)" in { f: Fixture =>
      import f._

      val ts = Array(
        2.05407309078346, 2.85886923211884, 2.89728554463089, 0.790480493540229, 0.548595335194215, 1.31367506547418,
        1.74407133897301, 4.06071962679526, 2.75651081738515, 0.604658754735038, 0.182607837501951, -1.262201503678,
        0.996560864201235, 2.74637817075616, 0.775004762296101, 0.906823901472144, 2.6839457174704, -0.0625841462071901,
        -1.09641353766956, 0.00479165991036998, 0.449351175604642, 3.53152043857777, 1.05206417605014, 2.7864942275709,
        -0.691007430091048, -1.02038488026721, -1.35124486835257, 0.0621976297222073, 2.82421545538541, 2.41312411015615,
        1.27711183784622, 0.0988204592711682, 1.50691474460298, 0.272037685359444, 1.9889742629239, 3.33907184622517,
        3.68134545243902, 0.751559686193563, 0.679120355399832, 0.428056866405207, 0.351341204822829, 1.33498418531095,
        3.04169869243666, 1.22542459625713, 1.35457091793328, 0.567124649501233, -1.95560538335988, -1.09014280752067,
        1.80062291606412, 0.588637569785287, 1.89212604693897, 1.38386740607786, 0.356716316822486, -2.07161693692556,
        4D, 1.44451323393473, 3.52551739267569, 3.16481926426412, 1.83839333727511, 0.827646664705546, 0.654351159135431,
        -0.00892931340717523, 0.678082675364184
      )

      // X:
      val X = vectorToMatrix( ts, 7, 9 )

      // E, S, L:
      val E_r = Array(
        -0.0907627955303747, 1.01938662397306, 1.7153606207031, 0.508734242238024, 0.723048984114528, 1.05744835689681,
        0.634974592796234, 1.52144373899958, 0.636387609902244, -0.816766677690375, -0.130107806055245, -0.998365425612053,
        0.744951709494425, 1.46231154911581, -0.226797959197785, 0.141398620170014, 1.77717624827034, -0.160279457424966,
        -0.921736144683016, -0.0307375549137413, -0.0215231023010388, 1.67109146682516, -0.344092782391524, 1.68469787539411,
        -0.86328701822436, -0.670845951339157, -1.39451774017965, -0.799528103709266, 0.889135246585203, 0.737525584567534,
        0.216923473185421, -0.161161265909894, 1.64839382264763, 0.0264997493930041, 0.980289570670967, 1.0549440532891,
        1.71882828500543, -0.505750385484114, 0.377784603637951, 0.610288197122763, 0.0752672973097475, 0.15206212152394,
        1.19645607409238, -0.200471255130702, 0.277569021928143, 0.381376624759279, -1.64980949817604, -1.16998330599701,
        0.925239888962673, -0.656964349367174, 0.843823792465116, 0.689801114362373, 0.200313968866586, -1.77717623601756,
        1.77717624450864, 0.810454981413976, 1.22657945137526, 1.23085136920443, 0.557077001335409, 0.539281927359977,
        0.878791698921523, -0.2497479761408, -0.491748238542012
      )

      val S_r = Array(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.318707767321735, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -0.173279535858878, 2.09194305945145, 0, 0, 0, 0, 0, 0, 0, 0
      )

      val L_r = Array(
        2.14483588631383, 1.83948260814578, 1.18192492392779, 0.281746251302205, -0.174453648920312, 0.256226708577366,
        1.10909674617677, 2.53927588779568, 2.12012320748291, 1.42142543242541, 0.312715643557195, -0.263836078065949,
        0.251609154706809, 1.28406662164035, 1.00180272149389, 0.76542528130213, 0.588061701878331, 0.0976953112177755,
        -0.174677392986544, 0.0355292148241113, 0.470874277905681, 1.86042897175261, 1.39615695844167, 1.10179635217679,
        0.172279588133313, -0.349538928928049, 0.0432728718270843, 0.861725733431473, 1.93508020880021, 1.67559852558861,
        1.0601883646608, 0.259981725181062, -0.14147907804465, 0.24553793596644, 1.00868469225294, 2.28412779293606,
        1.96251716743359, 1.25731007167768, 0.301335751761881, -0.182231330717556, 0.276073907513082, 1.18292206378701,
        1.84524261834428, 1.42589585138783, 1.07700189600514, 0.185748024741955, -0.305795885183839, 0.0798404984763351,
        0.875383027101449, 1.24560191915246, 1.04830225447385, 0.694066291715486, 0.1564023479559, -0.121161165049127,
        0.13088069603991, 0.634058252520755, 2.29893794130043, 1.93396789505969, 1.2813163359397, 0.288364737345569,
        -0.224440539786092, 0.240818662733625, 1.1698309139062
      )

      val E_matrix_r = vectorToMatrix( E_r, 7, 9 )
      val S_matrix_r = vectorToMatrix( S_r, 7, 9 )
      val L_matrix_r = vectorToMatrix( L_r, 7, 9 )

      val rsvd = RPCA( X, 1, 1.4 / 3 )
      val E = rsvd.E.getData
      val S = rsvd.S.getData
      val L = rsvd.L.getData

      matrixApproximatelyEquals( E_matrix_r, E, 0.0001 ) mustBe true
      matrixApproximatelyEquals( S_matrix_r, S, 0.0001 ) mustBe true
      matrixApproximatelyEquals( L_matrix_r, L, 0.0001 ) mustBe true
    }
  }
}


//public void testRSVD() {
//  System.out.println("Running Test: testRSVD");
//
//  // X:
//  double[] ts = new double[] {2.05407309078346,2.85886923211884,2.89728554463089,0.790480493540229,0.548595335194215,1.31367506547418,1.74407133897301,4.06071962679526,2.75651081738515,0.604658754735038,0.182607837501951,-1.262201503678,0.996560864201235,2.74637817075616,0.775004762296101,0.906823901472144,2.6839457174704,-0.0625841462071901,-1.09641353766956,0.00479165991036998,0.449351175604642,3.53152043857777,1.05206417605014,2.7864942275709,-0.691007430091048,-1.02038488026721,-1.35124486835257,0.0621976297222073,2.82421545538541,2.41312411015615,1.27711183784622,0.0988204592711682,1.50691474460298,0.272037685359444,1.9889742629239,3.33907184622517,3.68134545243902,0.751559686193563,0.679120355399832,0.428056866405207,0.351341204822829,1.33498418531095,3.04169869243666,1.22542459625713,1.35457091793328,0.567124649501233,-1.95560538335988,-1.09014280752067,1.80062291606412,0.588637569785287,1.89212604693897,1.38386740607786,0.356716316822486,-2.07161693692556,4,1.44451323393473,3.52551739267569,3.16481926426412,1.83839333727511,0.827646664705546,0.654351159135431,-0.00892931340717523,0.678082675364184};
//  double[][] X = VectorToMatrix(ts, 7, 9);
//
//  // E, S, L:
//  double[] E_r = new double[] {-0.0907627955303747,1.01938662397306,1.7153606207031,0.508734242238024,0.723048984114528,1.05744835689681,0.634974592796234,1.52144373899958,0.636387609902244,-0.816766677690375,-0.130107806055245,-0.998365425612053,0.744951709494425,1.46231154911581,-0.226797959197785,0.141398620170014,1.77717624827034,-0.160279457424966,-0.921736144683016,-0.0307375549137413,-0.0215231023010388,1.67109146682516,-0.344092782391524,1.68469787539411,-0.86328701822436,-0.670845951339157,-1.39451774017965,-0.799528103709266,0.889135246585203,0.737525584567534,0.216923473185421,-0.161161265909894,1.64839382264763,0.0264997493930041,0.980289570670967,1.0549440532891,1.71882828500543,-0.505750385484114,0.377784603637951,0.610288197122763,0.0752672973097475,0.15206212152394,1.19645607409238,-0.200471255130702,0.277569021928143,0.381376624759279,-1.64980949817604,-1.16998330599701,0.925239888962673,-0.656964349367174,0.843823792465116,0.689801114362373,0.200313968866586,-1.77717623601756,1.77717624450864,0.810454981413976,1.22657945137526,1.23085136920443,0.557077001335409,0.539281927359977,0.878791698921523,-0.2497479761408,-0.491748238542012};
//  double[] S_r = new double[] {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.318707767321735,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.173279535858878,2.09194305945145,0,0,0,0,0,0,0,0};
//  double[] L_r = new double[] {2.14483588631383,1.83948260814578,1.18192492392779,0.281746251302205,-0.174453648920312,0.256226708577366,1.10909674617677,2.53927588779568,2.12012320748291,1.42142543242541,0.312715643557195,-0.263836078065949,0.251609154706809,1.28406662164035,1.00180272149389,0.76542528130213,0.588061701878331,0.0976953112177755,-0.174677392986544,0.0355292148241113,0.470874277905681,1.86042897175261,1.39615695844167,1.10179635217679,0.172279588133313,-0.349538928928049,0.0432728718270843,0.861725733431473,1.93508020880021,1.67559852558861,1.0601883646608,0.259981725181062,-0.14147907804465,0.24553793596644,1.00868469225294,2.28412779293606,1.96251716743359,1.25731007167768,0.301335751761881,-0.182231330717556,0.276073907513082,1.18292206378701,1.84524261834428,1.42589585138783,1.07700189600514,0.185748024741955,-0.305795885183839,0.0798404984763351,0.875383027101449,1.24560191915246,1.04830225447385,0.694066291715486,0.1564023479559,-0.121161165049127,0.13088069603991,0.634058252520755,2.29893794130043,1.93396789505969,1.2813163359397,0.288364737345569,-0.224440539786092,0.240818662733625,1.1698309139062};
//
//  double[][] E_matrix_r = VectorToMatrix(E_r, 7, 9);
//  double[][] S_matrix_r = VectorToMatrix(S_r, 7, 9);
//  double[][] L_matrix_r = VectorToMatrix(L_r, 7, 9);
//
//  RPCA rsvd = new RPCA(X, 1, 1.4/3);
//
//  double[][] E = rsvd.getE().getData();
//  double[][] S = rsvd.getS().getData();
//  double[][] L = rsvd.getL().getData();
//
//  assertTrue(MatrixApproximatelyEquals(E_matrix_r, E, 0.0001));
//  assertTrue(MatrixApproximatelyEquals(S_matrix_r, S, 0.0001));
//  assertTrue(MatrixApproximatelyEquals(L_matrix_r, L, 0.0001));
//}
