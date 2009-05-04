/*
 * Copyright (c) 2009 Concurrent, Inc.
 *
 * This work has been released into the public domain
 * by the copyright holder. This applies worldwide.
 *
 * In case this is not legally possible:
 * The copyright holder grants any entity the right
 * to use this work for any purpose, without any
 * conditions, unless such conditions are required by law.
 */

package cascading.jdbc;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cascading.jdbc.db.DBConfiguration;
import cascading.tap.SinkMode;
import cascading.tap.Tap;
import cascading.tap.TapException;
import cascading.tap.hadoop.TapCollector;
import cascading.tap.hadoop.TapIterator;
import cascading.tuple.TupleEntryCollector;
import cascading.tuple.TupleEntryIterator;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Class JDBCTap is a {@link Tap} subclass that provides read and write access to a RDBMS. */
public class JDBCTap extends Tap
  {
  /** Field LOG */
  private static final Logger LOG = LoggerFactory.getLogger( JDBCTap.class );

  /** Field connectionUrl */
  String connectionUrl;
  /** Field username */
  String username;
  /** Field password */
  String password;
  /** Field driverClassName */
  String driverClassName;
  /** Field tableDesc */
  TableDesc tableDesc;

  /**
   * Constructor JDBCTap creates a new JDBCTap instance.
   * <p/>
   * Use this constructor for connecting to exesting tables that will be read from, or will be inserted/updated
   * into. By default it uses {@link SinkMode#APPEND}.
   *
   * @param connectionUrl   of type String
   * @param username        of type String
   * @param password        of type String
   * @param driverClassName of type String
   * @param tableName       of type String
   * @param scheme          of type JDBCScheme
   */
  public JDBCTap( String connectionUrl, String username, String password, String driverClassName, String tableName, JDBCScheme scheme )
    {
    this( connectionUrl, username, password, driverClassName, new TableDesc( tableName ), scheme, SinkMode.APPEND );
    }

  /**
   * Constructor JDBCTap creates a new JDBCTap instance.
   *
   * @param connectionUrl   of type String
   * @param driverClassName of type String
   * @param tableDesc       of type TableDesc
   * @param scheme          of type JDBCScheme
   * @param sinkMode        of type SinkMode
   */
  public JDBCTap( String connectionUrl, String driverClassName, TableDesc tableDesc, JDBCScheme scheme, SinkMode sinkMode )
    {
    this( connectionUrl, null, null, driverClassName, tableDesc, scheme, sinkMode );
    }

  /**
   * Constructor JDBCTap creates a new JDBCTap instance.
   *
   * @param connectionUrl   of type String
   * @param username        of type String
   * @param password        of type String
   * @param driverClassName of type String
   * @param tableDesc       of type TableDesc
   * @param scheme          of type JDBCScheme
   */
  public JDBCTap( String connectionUrl, String username, String password, String driverClassName, TableDesc tableDesc, JDBCScheme scheme )
    {
    this( connectionUrl, username, password, driverClassName, tableDesc, scheme, SinkMode.APPEND );
    }

  /**
   * Constructor JDBCTap creates a new JDBCTap instance.
   *
   * @param connectionUrl   of type String
   * @param username        of type String
   * @param password        of type String
   * @param driverClassName of type String
   * @param tableDesc       of type TableDesc
   * @param scheme          of type JDBCScheme
   * @param sinkMode        of type SinkMode
   */
  public JDBCTap( String connectionUrl, String username, String password, String driverClassName, TableDesc tableDesc, JDBCScheme scheme, SinkMode sinkMode )
    {
    super( scheme, sinkMode );
    this.connectionUrl = connectionUrl;
    this.username = username;
    this.password = password;
    this.driverClassName = driverClassName;
    this.tableDesc = tableDesc;

    if( tableDesc.getColumnDefs() == null && sinkMode != SinkMode.APPEND )
      throw new IllegalArgumentException( "cannot have sink mode REPLACE or KEEP without TableDesc column defs, use APPEND mode" );

    if( sinkMode != SinkMode.APPEND )
      LOG.warn( "using sink mode: {}, consider APPEND to prevent DROP TABLE from being called during Flow or Cascade setup", sinkMode );
    }

  /**
   * Constructor JDBCTap creates a new JDBCTap instance.
   *
   * @param connectionUrl   of type String
   * @param driverClassName of type String
   * @param tableDesc       of type TableDesc
   * @param scheme          of type JDBCScheme
   */
  public JDBCTap( String connectionUrl, String driverClassName, TableDesc tableDesc, JDBCScheme scheme )
    {
    this( connectionUrl, driverClassName, tableDesc, scheme, SinkMode.APPEND );
    }

  /**
   * Constructor JDBCTap creates a new JDBCTap instance that may only used as a data source.
   *
   * @param connectionUrl   of type String
   * @param username        of type String
   * @param password        of type String
   * @param driverClassName of type String
   * @param scheme          of type JDBCScheme
   */
  public JDBCTap( String connectionUrl, String username, String password, String driverClassName, JDBCScheme scheme )
    {
    super( scheme );
    this.connectionUrl = connectionUrl;
    this.username = username;
    this.password = password;
    this.driverClassName = driverClassName;
    }

  /**
   * Constructor JDBCTap creates a new JDBCTap instance.
   *
   * @param connectionUrl   of type String
   * @param driverClassName of type String
   * @param scheme          of type JDBCScheme
   */
  public JDBCTap( String connectionUrl, String driverClassName, JDBCScheme scheme )
    {
    this( connectionUrl, null, null, driverClassName, scheme );
    }

  /**
   * Method getTableName returns the tableName of this JDBCTap object.
   *
   * @return the tableName (type String) of this JDBCTap object.
   */
  public String getTableName()
    {
    return tableDesc.tableName;
    }

  /**
   * Method getPath returns the path of this JDBCTap object.
   *
   * @return the path (type Path) of this JDBCTap object.
   */
  public Path getPath()
    {
    return new Path( "jdbc:/" + connectionUrl.replaceAll( ":", "_" ) );
    }

  public TupleEntryIterator openForRead( JobConf conf ) throws IOException
    {
    return new TupleEntryIterator( getSourceFields(), new TapIterator( this, conf ) );
    }

  public TupleEntryCollector openForWrite( JobConf conf ) throws IOException
    {
    if( !isSink() )
      throw new TapException( "this tap may not be used as a sink, no TableDesc defined" );

    return new TapCollector( this, conf );
    }

  @Override
  public boolean isSink()
    {
    return tableDesc != null;
    }

  @Override
  public void sourceInit( JobConf conf ) throws IOException
    {
    // a hack for MultiInputFormat to see that there is a child format
    FileInputFormat.setInputPaths( conf, getPath() );

    if( username == null )
      DBConfiguration.configureDB( conf, driverClassName, connectionUrl );
    else
      DBConfiguration.configureDB( conf, driverClassName, connectionUrl, username, password );

    super.sourceInit( conf );
    }

  @Override
  public void sinkInit( JobConf conf ) throws IOException
    {
    if( !isSink() )
      return;

    // do not delete if initialized from within a task
    if( isReplace() && conf.get( "mapred.task.partition" ) == null && !deletePath( conf ) )
      throw new TapException( "unable to drop table: " + tableDesc.getTableName() );

    if( !makeDirs( conf ) )
      throw new TapException( "unable to create table: " + tableDesc.getTableName() );

    if( username == null )
      DBConfiguration.configureDB( conf, driverClassName, connectionUrl );
    else
      DBConfiguration.configureDB( conf, driverClassName, connectionUrl, username, password );

    super.sinkInit( conf );
    }

  private Connection createConnection()
    {
    try
      {
      LOG.info( "creating connection: {}", connectionUrl );

      Class.forName( driverClassName );

      Connection connection = null;

      if( username == null )
        connection = DriverManager.getConnection( connectionUrl );
      else
        connection = DriverManager.getConnection( connectionUrl, username, password );

      connection.setAutoCommit( false );

      return connection;
      }
    catch( ClassNotFoundException exception )
      {
      throw new TapException( "unable to load driver class: " + driverClassName, exception );
      }
    catch( SQLException exception )
      {
      throw new TapException( "unable to open connection: " + connectionUrl, exception );
      }
    }

  /**
   * Method executeUpdate allows for ad-hoc update statements to be sent to the remote RDBMS. The number of
   * rows updated will be returned, if applicable.
   *
   * @param updateString of type String
   * @return int
   */
  public int executeUpdate( String updateString )
    {
    Connection connection = null;
    int result;

    try
      {
      connection = createConnection();

      try
        {
        LOG.info( "executing update: {}", updateString );

        Statement statement = connection.createStatement();

        result = statement.executeUpdate( updateString );

        connection.commit();
        statement.close();
        }
      catch( SQLException exception )
        {
        throw new TapException( "unable to execute update statement: " + updateString, exception );
        }
      }
    finally
      {
      try
        {
        if( connection != null )
          connection.close();
        }
      catch( SQLException exception )
        {
        // ignore
        LOG.warn( "ignoring connection close exception", exception );
        }
      }

    return result;
    }

  /**
   * Method executeQuery allows for ad-hoc queries to be sent to the remove RDBMS. A value
   * of -1 for returnResults will return a List of all results from the query, a value of 0 will return an empty List.
   *
   * @param queryString   of type String
   * @param returnResults of type int
   * @return List
   */
  public List<Object[]> executeQuery( String queryString, int returnResults )
    {
    Connection connection = null;
    List<Object[]> result = Collections.emptyList();

    try
      {
      connection = createConnection();

      try
        {
        LOG.info( "executing query: {}", queryString );

        Statement statement = connection.createStatement();

        ResultSet resultSet = statement.executeQuery( queryString ); // we don't care about results

        if( returnResults != 0 )
          result = copyResultSet( resultSet, returnResults == -1 ? Integer.MAX_VALUE : returnResults );

        connection.commit();
        statement.close();
        }
      catch( SQLException exception )
        {
        throw new TapException( "unable to execute query statement: " + queryString, exception );
        }
      }
    finally
      {
      try
        {
        if( connection != null )
          connection.close();
        }
      catch( SQLException exception )
        {
        // ignore
        LOG.warn( "ignoring connection close exception", exception );
        }
      }

    return result;
    }

  private List<Object[]> copyResultSet( ResultSet resultSet, int length ) throws SQLException
    {
    List<Object[]> results = new ArrayList<Object[]>( length );
    int size = resultSet.getMetaData().getColumnCount();

    int count = 0;

    while( resultSet.next() && count < length )
      {
      count++;

      Object[] row = new Object[size];

      for( int i = 0; i < row.length; i++ )
        row[ i ] = resultSet.getObject( i + 1 );

      results.add( row );
      }

    return results;
    }

  public boolean makeDirs( JobConf conf ) throws IOException
    {
    if( pathExists( conf ) )
      return true;

    try
      {
      LOG.info( "creating table: {}", tableDesc.tableName );

      executeUpdate( tableDesc.getCreateTableStatement() );
      }
    catch( TapException exception )
      {
      LOG.warn( "unable to create table: {}", tableDesc.tableName, exception.getCause() );

      return false;
      }

    return pathExists( conf );
    }

  public boolean deletePath( JobConf conf ) throws IOException
    {
    if( !isSink() )
      return false;

    if( !pathExists( conf ) )
      return true;

    try
      {
      LOG.info( "deleting table: {}", tableDesc.tableName );

      executeUpdate( tableDesc.getTableDropStatement() );
      }
    catch( TapException exception )
      {
      LOG.warn( "unable to drop table: {}", tableDesc.tableName, exception.getCause() );

      return false;
      }

    return !pathExists( conf );
    }

  public boolean pathExists( JobConf conf ) throws IOException
    {
    if( !isSink() )
      return true;

    try
      {
      LOG.info( "test table exists: {}", tableDesc.tableName );

      executeQuery( tableDesc.getTableExistsQuery(), 0 );
      }
    catch( TapException exception )
      {
      return false;
      }

    return true;
    }

  public long getPathModified( JobConf conf ) throws IOException
    {
    return System.currentTimeMillis();
    }

  @Override
  public String toString()
    {
    return "JDBCTap{" + "connectionUrl='" + connectionUrl + '\'' + ", driverClassName='" + driverClassName + '\'' + ", tableDesc=" + tableDesc + '}';
    }
  }
