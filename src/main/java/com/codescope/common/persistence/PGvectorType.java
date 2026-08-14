package com.codescope.common.persistence;

import com.pgvector.PGvector;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;

/**
 * float[] <-> Postgres vector 컬럼 매핑용 Hibernate UserType.
 *
 * 왜 필요한가: Hibernate 6.6.53.Final(Spring Boot 3.5.16 기준) 내장
 * @JdbcTypeCode(SqlTypes.VECTOR)를 실제로 붙여 INSERT를 실행해보면
 * float[] 파라미터를 VARBINARY(bytea)로 바인딩해
 * "column \"embedding\" is of type vector but expression is of type bytea"
 * 에러가 난다(실측 확인, Hibernate 자체 한계). pgvector-java의 PGvector는
 * PGobject를 상속해 드라이버가 인식하는 텍스트 포맷("[0.1,0.2,...]")으로
 * 자기 자신을 직렬화하므로, PreparedStatement.setObject()에 PGvector
 * 인스턴스를 직접 넘겨 이 클래스가 바인딩 경로를 명시적으로 제어한다.
 */
public class PGvectorType implements UserType<float[]> {

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<float[]> returnedClass() {
        return float[].class;
    }

    @Override
    public boolean equals(float[] x, float[] y) {
        return Arrays.equals(x, y);
    }

    @Override
    public int hashCode(float[] x) {
        return Arrays.hashCode(x);
    }

    @Override
    public float[] nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner) throws SQLException {
        Object value = rs.getObject(position);
        if (value == null) {
            return null;
        }
        try {
            return new PGvector(value.toString()).toArray();
        } catch (SQLException e) {
            throw new HibernateException("vector 컬럼 값을 파싱하지 못했습니다: " + value, e);
        }
    }

    @Override
    public void nullSafeSet(PreparedStatement st, float[] value, int index, SharedSessionContractImplementor session) throws SQLException {
        st.setObject(index, value == null ? null : new PGvector(value));
    }

    @Override
    public float[] deepCopy(float[] value) {
        return value == null ? null : value.clone();
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(float[] value) {
        return deepCopy(value);
    }

    @Override
    public float[] assemble(Serializable cached, Object owner) {
        return deepCopy((float[]) cached);
    }

    @Override
    public float[] replace(float[] detached, float[] managed, Object owner) {
        return deepCopy(detached);
    }
}
