/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.accumulo.iterators

import org.apache.accumulo.core.client.mock.MockInstance
import org.apache.accumulo.core.client.security.tokens.PasswordToken
import org.apache.accumulo.core.security.Authorizations
import org.geotools.data.DataStoreFinder
import org.geotools.factory.Hints
import org.joda.time.{DateTime, DateTimeZone, Period}
import org.junit.runner.RunWith
import org.locationtech.geomesa.accumulo.TestWithDataStore
import org.locationtech.geomesa.accumulo.data.AccumuloDataStore
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.security.SecurityUtils
import org.locationtech.geomesa.utils.collection.SelfClosingIterator
import org.locationtech.geomesa.utils.text.WKTUtils
import org.opengis.feature.simple.SimpleFeature
import org.opengis.filter.Filter
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class DtgAgeOffTest extends Specification with TestWithDataStore {

  sequential

  override val spec = "some_id:String,dtg:Date,geom:Point:srid=4326"
  override val tableSharing = false

  "DTGAgeOff" should {

    def configAgeOff(ads: AccumuloDataStore, days: Int): Unit = {
      DtgAgeOffIterator.clear(ads, ads.getSchema(sft.getTypeName))
      DtgAgeOffIterator.set(ads, ads.getSchema(sft.getTypeName), Period.days(days), "dtg")
    }

    val today: DateTime = DateTime.now(DateTimeZone.UTC)

    def createSF(i: Int, id: String, vis: Option[String]): SimpleFeature = {
      val geom = WKTUtils.read(s"POINT($i $i)")
      val arr = Array[AnyRef](
        id,
        today.minusDays(i).toDate,
        geom
      )
      val sf = ScalaSimpleFeature.create(sft, id, arr: _*)
      sf.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
      vis.map(SecurityUtils.setFeatureVisibility(sf, _))
      sf
    }

    def testDays(d: Int): Seq[SimpleFeature] = {
      configAgeOff(ds, d)
      SelfClosingIterator(ds.getFeatureSource(sft.getTypeName).getFeatures(Filter.INCLUDE).features).toSeq
    }

    "run at scan time" >> {
      addFeatures((1 to 10).map(i => createSF(i, s"id_$i", Some("A"))))
      testDays(11) must haveSize(10)
      testDays(10) must haveSize(9)
      testDays(5) must haveSize(4)
      testDays(1) must haveSize(0)

      success
    }

    "respect vis with ageoff (vis trumps ageoff)" >> {
      // these exist but shouldn't be read!
      addFeatures((1 to 10).map(i => createSF(i, s"anotherid_$i", Some("D"))))
      testDays(11) must haveSize(10)
      testDays(10) must haveSize(9)
      testDays(5) must haveSize(4)
      testDays(1) must haveSize(0)

      val dsWithExtraAuth = {
        val connWithExtraAuth = {
          val mockInstance = new MockInstance("mycloud")
          val mockConnector = mockInstance.getConnector("user2", new PasswordToken("password2"))
          mockConnector.securityOperations().changeUserAuthorizations("user2", new Authorizations("A,B,C,D"))
          mockConnector
        }
        import scala.collection.JavaConverters._
        DataStoreFinder.getDataStore(Map(
          "connector" -> connWithExtraAuth,
          "caching"   -> false,
          // note the table needs to be different to prevent testing errors
          "tableName" -> sftName).asJava).asInstanceOf[AccumuloDataStore]
      }

      def testWithExtraAuth(d: Int): Seq[SimpleFeature] = {
        configAgeOff(dsWithExtraAuth, d)
        SelfClosingIterator(dsWithExtraAuth.getFeatureSource(sft.getTypeName).getFeatures(Filter.INCLUDE).features).toSeq
      }

      testWithExtraAuth(11) must haveSize(20)
      testWithExtraAuth(10) must haveSize(18)
      testWithExtraAuth(5) must haveSize(8)
      testWithExtraAuth(1) must haveSize(0)

      // these can be read
      addFeatures((1 to 10).map(i => createSF(i, s"anotherid_$i", Some("C"))))
      testDays(11) must haveSize(20)
      testDays(10) must haveSize(18)
      testDays(5) must haveSize(8)
      testDays(1) must haveSize(0)

      // these are 3x
      testWithExtraAuth(11) must haveSize(30)
      testWithExtraAuth(10) must haveSize(27)
      testWithExtraAuth(5) must haveSize(12)
      testWithExtraAuth(1) must haveSize(0)
    }
  }
}