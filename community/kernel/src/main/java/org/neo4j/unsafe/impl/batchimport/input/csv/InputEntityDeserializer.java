/**
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.unsafe.impl.batchimport.input.csv;

import java.io.IOException;
import java.util.Arrays;

import org.neo4j.csv.reader.CharSeeker;
import org.neo4j.csv.reader.Extractors;
import org.neo4j.csv.reader.Mark;
import org.neo4j.function.Function;
import org.neo4j.helpers.Exceptions;
import org.neo4j.helpers.collection.PrefetchingIterator;
import org.neo4j.unsafe.impl.batchimport.InputIterator;
import org.neo4j.unsafe.impl.batchimport.input.DataException;
import org.neo4j.unsafe.impl.batchimport.input.InputEntity;
import org.neo4j.unsafe.impl.batchimport.input.InputException;
import org.neo4j.unsafe.impl.batchimport.input.UnexpectedEndOfInputException;

import static java.lang.String.format;
import static java.util.Arrays.copyOf;

/**
 * Converts a line of csv data into an {@link InputEntity} (either a node or relationship).
 * Does so by seeking values, using {@link CharSeeker}, interpreting the values using a {@link Header}.
 */
abstract class InputEntityDeserializer<ENTITY extends InputEntity> extends PrefetchingIterator<ENTITY>
        implements InputIterator<ENTITY>
{
    protected final Header header;
    private final CharSeeker data;
    private final Mark mark = new Mark();
    private final int delimiter;
    private final Function<ENTITY,ENTITY> decorator;

    // Data
    // holder of properties, alternating key/value. Will grow with the entity having most properties.
    private Object[] properties = new Object[10*2];
    private int propertiesCursor;

    InputEntityDeserializer( Header header, CharSeeker data, int delimiter, Function<ENTITY,ENTITY> decorator )
    {
        this.header = header;
        this.data = data;
        this.delimiter = delimiter;
        this.decorator = decorator;
    }

    @Override
    protected ENTITY fetchNextOrNull()
    {
        // Read a CSV "line" and convert the values into what they semantically mean.
        int fieldIndex = 0;
        Header.Entry[] entries = header.entries();
        try
        {
            for ( ; fieldIndex < entries.length; fieldIndex++ )
            {
                // Seek the next value
                if ( !data.seek( mark, delimiter ) )
                {
                    if ( fieldIndex > 0 )
                    {
                        throw new UnexpectedEndOfInputException( "Near " + mark );
                    }
                    // We're just at the end
                    return null;
                }

                // Extract it, type according to our header
                Header.Entry entry = entries[fieldIndex];
                Object value = data.tryExtract( mark, entry.extractor() )
                        ? entry.extractor().value() : null;
                boolean handled = true;
                switch ( entry.type() )
                {
                case PROPERTY:
                    addProperty( entry, value );
                    break;
                case IGNORE: // value ignored
                    break;
                default:
                    handled = false;
                    break;
                }

                // This is an abstract base class, so send on to sub classes if we didn't know
                // how to handle a header entry of this type
                if ( !handled )
                {
                    handleValue( entry, value );
                }

                if ( mark.isEndOfLine() )
                {   // We're at the end of the line, break and return an entity with what we have.
                    break;
                }
            }

            // When we have everything, create an input entity out of it
            ENTITY entity = convertToInputEntity( properties() );

            // If there are more values on this line, ignore them
            // TODO perhaps log about them?
            while ( !mark.isEndOfLine() )
            {
                data.seek( mark, delimiter );
            }

            entity = decorator.apply( entity );
            validate( entity );
            return entity;
        }
        catch ( IOException e )
        {
            throw new InputException( "Unable to read more data from input stream", e );
        }
        catch ( final RuntimeException e )
        {
            String stringValue = null;
            try
            {
                Extractors extractors = new Extractors( '?' );
                if ( data.tryExtract( mark, extractors.string() ) )
                {
                    stringValue = extractors.string().value();
                }
            }
            catch ( Exception e1 )
            {   // OK
            }

            String message = format( "ERROR in input" +
                    "%n  data source: %s" +
                    "%n  in field: %s" +
                    "%n  for header: %s" +
                    "%n  raw field value: %s" +
                    "%n  original error: %s",
                    data, entries[fieldIndex] + ":" + (fieldIndex+1), header,
                    stringValue != null ? stringValue : "??",
                    e.getMessage() );
            if ( e instanceof InputException )
            {
                throw Exceptions.withMessage( e, message );
            }
            throw new InputException( message, e );
        }
        finally
        {
            propertiesCursor = 0;
        }
    }

    /**
     * Called after the entity has been fully populated.
     * @throws DataException on validation error.
     */
    protected void validate( ENTITY entity )
    {   // No default validation
    }

    protected void addProperty( Header.Entry entry, Object value )
    {
        if ( value != null )
        {
            ensurePropertiesArrayCapacity( propertiesCursor+2 );
            properties[propertiesCursor++] = entry.name();
            properties[propertiesCursor++] = value;
        }
        // else it's fine because no value was specified
    }

    private Object[] properties()
    {
        return propertiesCursor > 0
                ? copyOf( properties, propertiesCursor )
                : InputEntity.NO_PROPERTIES;
    }

    private void ensurePropertiesArrayCapacity( int length )
    {
        if ( length > properties.length )
        {
            properties = Arrays.copyOf( properties, length );
        }
    }

    @Override
    public void close()
    {
        try
        {
            data.close();
        }
        catch ( IOException e )
        {
            throw new InputException( "Unable to close data iterator", e );
        }
    }

    protected abstract ENTITY convertToInputEntity( Object[] properties );

    protected abstract void handleValue( Header.Entry entry, Object value );

    @Override
    public long position()
    {
        return data.position();
    }

    @Override
    public String toString()
    {
        return data.toString();
    }
}
